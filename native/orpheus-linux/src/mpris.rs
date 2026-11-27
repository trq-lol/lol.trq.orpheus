// SPDX-License-Identifier: MIT

//! The worker: tracks MPRIS players from bus signals, reads what signals leave out, runs commands, and
//! serializes state. Only the position is polled, because MPRIS never announces it.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use zbus::Message;
use zbus::blocking::{Connection, MessageIterator, connection};
use zbus::message::Type;
use zbus::zvariant::{ObjectPath, OwnedValue, Value};

use crate::{Command, Shared, wire};

const PREFIX: &str = "org.mpris.MediaPlayer2.";
const OBJECT: &str = "/org/mpris/MediaPlayer2";
const ROOT: &str = "org.mpris.MediaPlayer2";
const PLAYER: &str = "org.mpris.MediaPlayer2.Player";
const PROPERTIES: &str = "org.freedesktop.DBus.Properties";
const DBUS: &str = "org.freedesktop.DBus";
const DBUS_PATH: &str = "/org/freedesktop/DBus";
const NO_TRACK: &str = "/org/mpris/MediaPlayer2/TrackList/NoTrack";
const CALL_TIMEOUT: Duration = Duration::from_secs(3);
const POSITION_INTERVAL: Duration = Duration::from_secs(5);
/// Java gives up on a command after this long; running it later would surprise the user.
const COMMAND_LIFETIME: Duration = Duration::from_secs(5);
const MAX_PLAYERS: usize = 64;
/// How often a property read that a newer signal overtook is asked again before it is used anyway.
const MAX_RETRIES: u32 = 4;
const MAX_TEXT_BYTES: usize = 4096;
const MAX_LIST: usize = 64;

// Bit positions match the ordinals of the Java `Control` enum.
const PLAY: u32 = 1 << 0;
const PAUSE: u32 = 1 << 1;
const TOGGLE: u32 = 1 << 2;
const STOP: u32 = 1 << 3;
const NEXT: u32 = 1 << 4;
const PREVIOUS: u32 = 1 << 5;
const SEEK: u32 = 1 << 6;
const SHUFFLE: u32 = 1 << 9;
const REPEAT: u32 = 1 << 10;
const RATE: u32 = 1 << 11;
const VOLUME: u32 = 1 << 12;

/// Everything the worker reacts to, in arrival order.
pub enum Event {
    Command(Command),
    Signal(Message),
    Lost,
    Names(Vec<String>),
    Identity { name: String, generation: u64, identity: String },
    All { name: String, generation: u64, version: u64, retries: u32, properties: HashMap<String, OwnedValue> },
    Position { name: String, generation: u64, version: u64, micros: i64, at: Instant },
}

#[derive(Default, Clone, PartialEq)]
struct Metadata {
    track_id: String,
    length_micros: i64,
    art_url: String,
    title: String,
    artists: Vec<String>,
    album: String,
    album_artists: Vec<String>,
    track_number: i32,
    url: String,
}

struct Player {
    id: u32,
    generation: u64,
    /// Counts signals, so a reply asked for before the latest signal is not applied over it.
    version: u64,
    owner: String,
    identity: String,
    loaded: bool,
    status: String,
    loop_status: Option<String>,
    rate: Option<f64>,
    shuffle: Option<bool>,
    volume: Option<f64>,
    minimum_rate: Option<f64>,
    maximum_rate: Option<f64>,
    can_go_next: Option<bool>,
    can_go_previous: Option<bool>,
    can_play: Option<bool>,
    can_pause: Option<bool>,
    can_seek: Option<bool>,
    can_control: Option<bool>,
    metadata: Option<Metadata>,
    position_micros: i64,
    position_at: Instant,
}

impl Player {
    fn playing(&self) -> bool {
        self.status == "Playing"
    }

    fn current_micros(&self) -> i64 {
        if !self.playing() {
            return self.position_micros;
        }
        let rate = self.rate.unwrap_or(1.0);
        self.position_micros + (self.position_at.elapsed().as_micros() as f64 * rate) as i64
    }

    fn song(&self) -> Option<(String, String)> {
        self.metadata.as_ref().map(|m| (m.track_id.clone(), m.title.clone()))
    }

    /// Takes over the properties that are used; a new song starts at its beginning until the player says
    /// otherwise.
    fn merge(&mut self, changed: &HashMap<String, OwnedValue>) {
        let before = self.song();
        for (name, value) in changed {
            let value = strip(value);
            let used = match name.as_str() {
                "PlaybackStatus" => {
                    self.status = text(value).unwrap_or_default();
                    true
                }
                "LoopStatus" => {
                    self.loop_status = text(value);
                    true
                }
                "Rate" => set(&mut self.rate, number(value)),
                "Shuffle" => set(&mut self.shuffle, boolean(value)),
                "Volume" => set(&mut self.volume, number(value)),
                "MinimumRate" => set(&mut self.minimum_rate, number(value)),
                "MaximumRate" => set(&mut self.maximum_rate, number(value)),
                "CanGoNext" => set(&mut self.can_go_next, boolean(value)),
                "CanGoPrevious" => set(&mut self.can_go_previous, boolean(value)),
                "CanPlay" => set(&mut self.can_play, boolean(value)),
                "CanPause" => set(&mut self.can_pause, boolean(value)),
                "CanSeek" => set(&mut self.can_seek, boolean(value)),
                "CanControl" => set(&mut self.can_control, boolean(value)),
                "Metadata" => {
                    self.metadata = Some(metadata(value));
                    true
                }
                _ => false,
            };
            self.loaded |= used;
        }
        if changed.contains_key("Metadata") && before.is_some() && before != self.song() {
            self.anchor(0);
        }
    }

    fn anchor(&mut self, micros: i64) {
        self.position_micros = micros;
        self.position_at = Instant::now();
    }

    fn controls(&self) -> u32 {
        if self.can_control == Some(false) {
            return 0;
        }
        let mut controls = STOP;
        let play = self.can_play == Some(true);
        let pause = self.can_pause == Some(true);
        if play {
            controls |= PLAY;
        }
        if pause {
            controls |= PAUSE;
        }
        if play || pause {
            controls |= TOGGLE;
        }
        if self.can_go_next == Some(true) {
            controls |= NEXT;
        }
        if self.can_go_previous == Some(true) {
            controls |= PREVIOUS;
        }
        if self.can_seek == Some(true) {
            controls |= SEEK;
        }
        if self.shuffle.is_some() {
            controls |= SHUFFLE;
        }
        if self.loop_status.is_some() {
            controls |= REPEAT;
        }
        if let (Some(min), Some(max)) = (self.minimum_rate, self.maximum_rate)
            && max > min
        {
            controls |= RATE;
        }
        if self.volume.is_some() {
            controls |= VOLUME;
        }
        controls
    }
}

fn set<T>(slot: &mut Option<T>, value: Option<T>) -> bool {
    *slot = value;
    true
}

fn strip<'a>(value: &'a Value<'a>) -> &'a Value<'a> {
    match value {
        Value::Value(inner) => strip(inner),
        other => other,
    }
}

fn cap(value: &str) -> String {
    if value.len() <= MAX_TEXT_BYTES {
        return value.to_string();
    }
    let mut end = MAX_TEXT_BYTES;
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_string()
}

fn text(value: &Value) -> Option<String> {
    match strip(value) {
        Value::Str(s) => Some(cap(s.as_str())),
        Value::ObjectPath(p) => Some(cap(p.as_str())),
        _ => None,
    }
}

fn number(value: &Value) -> Option<f64> {
    match strip(value) {
        Value::F64(v) => Some(*v),
        Value::I64(v) => Some(*v as f64),
        Value::U64(v) => Some(*v as f64),
        Value::I32(v) => Some(*v as f64),
        Value::U32(v) => Some(*v as f64),
        Value::I16(v) => Some(*v as f64),
        Value::U16(v) => Some(*v as f64),
        Value::U8(v) => Some(*v as f64),
        _ => None,
    }
    .filter(|v| v.is_finite())
}

fn boolean(value: &Value) -> Option<bool> {
    match strip(value) {
        Value::Bool(v) => Some(*v),
        _ => None,
    }
}

fn strings(value: &Value) -> Vec<String> {
    match strip(value) {
        Value::Array(array) => array.inner().iter().filter_map(text).take(MAX_LIST).collect(),
        other => text(other).into_iter().collect(),
    }
}

fn metadata(value: &Value) -> Metadata {
    let mut m = Metadata { length_micros: -1, ..Metadata::default() };
    let Value::Dict(dict) = strip(value) else {
        return m;
    };
    for (key, value) in dict.iter() {
        let Some(key) = text(key) else {
            continue;
        };
        match key.as_str() {
            "mpris:trackid" => m.track_id = text(value).unwrap_or_default(),
            "mpris:length" => m.length_micros = number(value).map_or(-1, |v| v as i64),
            // art is often a data uri, so it is never cut
            "mpris:artUrl" => {
                if let Value::Str(s) = strip(value) {
                    m.art_url = s.as_str().to_string();
                }
            }
            "xesam:title" => m.title = text(value).unwrap_or_default(),
            "xesam:artist" => m.artists = strings(value),
            "xesam:album" => m.album = text(value).unwrap_or_default(),
            "xesam:albumArtist" => m.album_artists = strings(value),
            "xesam:trackNumber" => m.track_number = number(value).map_or(0, |v| v as i32),
            "xesam:url" => m.url = text(value).unwrap_or_default(),
            _ => {}
        }
    }
    m
}

struct Worker<'a> {
    shared: &'a Arc<Shared>,
    connection: Connection,
    players: Vec<(String, Player)>,
    next_id: u32,
    next_generation: u64,
    dirty: bool,
}

pub fn run(shared: &Arc<Shared>) -> Result<(), String> {
    let connection = connection::Builder::session()
        .and_then(|builder| builder.method_timeout(CALL_TIMEOUT).build())
        .map_err(|e| format!("no session bus: {e}"))?;
    let owners = "type='signal',sender='org.freedesktop.DBus',interface='org.freedesktop.DBus',\
                  member='NameOwnerChanged',arg0namespace='org.mpris.MediaPlayer2'";
    let players = format!("type='signal',path='{OBJECT}'");
    let mut readers = Vec::new();
    for rule in [owners.to_string(), players] {
        let iterator = MessageIterator::for_match_rule(rule.as_str(), &connection, Some(256))
            .map_err(|e| format!("the session bus refused a match rule: {e}"))?;
        let shared = shared.clone();
        readers.push(
            std::thread::Builder::new()
                .name("orpheus-mpris-read".into())
                .spawn(move || read(&shared, iterator))
                .map_err(|_| "the MPRIS reader thread could not be started".to_string())?,
        );
    }
    let mut worker = Worker { shared, connection, players: Vec::new(), next_id: 1, next_generation: 1, dirty: true };
    let outcome = worker.serve();
    let Worker { connection, .. } = worker;
    let _ = connection.close();
    for reader in readers {
        let _ = reader.join();
    }
    outcome
}

fn read(shared: &Shared, iterator: MessageIterator) {
    for message in iterator {
        match message {
            Ok(message) => shared.post(Event::Signal(message)),
            Err(_) => break,
        }
    }
    if !shared.stopping() {
        shared.post(Event::Lost);
    }
}

impl Worker<'_> {
    fn serve(&mut self) -> Result<(), String> {
        let reply = self
            .connection
            .call_method(Some(DBUS), DBUS_PATH, Some(DBUS), "ListNames", &())
            .map_err(|e| format!("the session bus did not list names: {e}"))?;
        let names: Vec<String> = reply.body().deserialize().map_err(|e| format!("bad name list: {e}"))?;
        self.shared.publish(wire::status(true, ""));
        for name in names {
            self.add(name, None);
        }
        let mut next_refresh = Instant::now() + POSITION_INTERVAL;
        loop {
            if self.dirty {
                self.dirty = false;
                self.publish();
            }
            let any_playing = self.players.iter().any(|(_, p)| p.playing());
            let Some(events) = self.shared.take(any_playing.then_some(next_refresh)) else {
                return Ok(());
            };
            if !any_playing {
                next_refresh = Instant::now() + POSITION_INTERVAL;
            } else if Instant::now() >= next_refresh {
                next_refresh = Instant::now() + POSITION_INTERVAL;
                let playing: Vec<String> =
                    self.players.iter().filter(|(_, p)| p.playing()).map(|(n, _)| n.clone()).collect();
                for name in playing {
                    self.read_position(&name);
                }
            }
            for event in events {
                if let Event::Lost = event {
                    return Err("the session bus connection ended".to_string());
                }
                self.handle(event);
            }
        }
    }

    fn find(&mut self, name: &str, generation: u64) -> Option<&mut Player> {
        self.players.iter_mut().find(|(n, p)| n == name && p.generation == generation).map(|(_, p)| p)
    }

    fn handle(&mut self, event: Event) {
        match event {
            Event::Command(command) => self.command(command),
            Event::Signal(message) => self.signal(&message),
            Event::Lost => {}
            Event::Names(names) => {
                for name in names {
                    self.add(name, None);
                }
                self.dirty = true;
            }
            Event::Identity { name, generation, identity } => {
                if let Some(player) = self.find(&name, generation) {
                    player.identity = identity;
                    self.dirty = true;
                }
            }
            Event::All { name, generation, version, retries, properties } => {
                if let Some(player) = self.find(&name, generation) {
                    if player.version != version && retries < MAX_RETRIES {
                        let current = player.version;
                        self.load_all(name, generation, current, retries + 1);
                        return;
                    }
                    player.merge(&properties);
                    if let Some(micros) = properties.get("Position").and_then(|v| number(v)) {
                        player.anchor(micros as i64);
                    }
                    self.dirty = true;
                }
            }
            Event::Position { name, generation, version, micros, at } => {
                if let Some(player) = self.find(&name, generation)
                    && player.version == version
                {
                    player.position_micros = micros;
                    player.position_at = at;
                    self.dirty = true;
                }
            }
        }
    }

    /// Runs `call` on a helper thread so a frozen player cannot stall the worker.
    fn later(&self, call: impl FnOnce(&Connection) -> Option<Event> + Send + 'static) {
        let connection = self.connection.clone();
        let shared = self.shared.clone();
        let _ = std::thread::Builder::new().name("orpheus-mpris-call".into()).spawn(move || {
            if let Some(event) = call(&connection) {
                shared.post(event);
            }
        });
    }

    fn add(&mut self, name: String, owner: Option<String>) {
        if !name.starts_with(PREFIX)
            || name == format!("{PREFIX}playerctld")
            || self.players.iter().any(|(n, _)| *n == name)
            || self.players.len() >= MAX_PLAYERS
        {
            return;
        }
        // the bus daemon answers this itself, so it cannot hang on a frozen player
        let owner = owner.or_else(|| {
            let reply = self
                .connection
                .call_method(Some(DBUS), DBUS_PATH, Some(DBUS), "GetNameOwner", &(name.as_str()))
                .ok()?;
            reply.body().deserialize::<String>().ok()
        });
        let Some(owner) = owner.filter(|o| !o.is_empty()) else {
            return;
        };
        let generation = self.next_generation;
        self.next_generation += 1;
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1).max(1);
        self.players.push((
            name.clone(),
            Player {
                id,
                generation,
                version: 0,
                owner,
                identity: String::new(),
                loaded: false,
                status: String::new(),
                loop_status: None,
                rate: None,
                shuffle: None,
                volume: None,
                minimum_rate: None,
                maximum_rate: None,
                can_go_next: None,
                can_go_previous: None,
                can_play: None,
                can_pause: None,
                can_seek: None,
                can_control: None,
                metadata: None,
                position_micros: 0,
                position_at: Instant::now(),
            },
        ));
        let n = name.clone();
        self.later(move |c| {
            let all = get_all(c, &n, ROOT)?;
            let identity = all.get("Identity").and_then(|v| text(v))?;
            Some(Event::Identity { name: n, generation, identity })
        });
        self.load_all(name, generation, 0, 0);
    }

    fn load_all(&self, name: String, generation: u64, version: u64, retries: u32) {
        self.later(move |c| {
            let properties = get_all(c, &name, PLAYER)?;
            Some(Event::All { name, generation, version, retries, properties })
        });
    }

    fn read_position(&self, name: &str) {
        let Some((generation, version)) =
            self.players.iter().find(|(n, _)| n == name).map(|(_, p)| (p.generation, p.version))
        else {
            return;
        };
        let name = name.to_string();
        self.later(move |c| {
            let asked = Instant::now();
            let reply =
                c.call_method(Some(name.as_str()), OBJECT, Some(PROPERTIES), "Get", &(PLAYER, "Position")).ok()?;
            let answered = Instant::now();
            let value: OwnedValue = reply.body().deserialize().ok()?;
            let micros = number(&value)? as i64;
            Some(Event::Position { name, generation, version, micros, at: asked + (answered - asked) / 2 })
        });
    }

    /// Fills the table again after a player left a full one.
    fn add_listed(&self) {
        self.later(|c| {
            let reply = c.call_method(Some(DBUS), DBUS_PATH, Some(DBUS), "ListNames", &()).ok()?;
            Some(Event::Names(reply.body().deserialize().ok()?))
        });
    }

    fn signal(&mut self, message: &Message) {
        let header = message.header();
        if header.message_type() != Type::Signal {
            return;
        }
        let (Some(member), sender) = (header.member(), header.sender()) else {
            return;
        };
        let member = member.as_str();
        let sender = sender.map(|s| s.as_str()).unwrap_or_default();
        let interface = header.interface().map(|i| i.as_str()).unwrap_or_default();
        if member == "NameOwnerChanged" && interface == DBUS && sender == DBUS {
            let Ok((name, _, new_owner)) = message.body().deserialize::<(String, String, String)>() else {
                return;
            };
            let full = self.players.len() >= MAX_PLAYERS;
            let before = self.players.len();
            self.players.retain(|(n, _)| *n != name);
            if new_owner.is_empty() {
                if self.players.len() != before {
                    self.dirty = true;
                    if full {
                        self.add_listed();
                    }
                }
            } else {
                self.dirty |= self.players.len() != before;
                self.add(name, Some(new_owner));
            }
            return;
        }
        let Some(index) = self.players.iter().rposition(|(_, p)| !p.owner.is_empty() && p.owner == sender) else {
            return;
        };
        let (name, player) = &mut self.players[index];
        let name = name.clone();
        player.version += 1;
        if member == "Seeked" {
            if let Ok(micros) = message.body().deserialize::<i64>() {
                player.anchor(micros);
                self.dirty = true;
            }
        } else if member == "PropertiesChanged" {
            let Ok((interface, changed, invalidated)) =
                message.body().deserialize::<(String, HashMap<String, OwnedValue>, Vec<String>)>()
            else {
                return;
            };
            if interface != PLAYER {
                return;
            }
            let moved = ["PlaybackStatus", "Metadata", "Rate"].iter().any(|k| changed.contains_key(*k));
            if moved {
                // freeze the position where it is now: the new state must not start from an old measurement
                let now = player.current_micros();
                player.anchor(now);
            }
            player.merge(&changed);
            let (generation, version) = (player.generation, player.version);
            if !invalidated.is_empty() {
                self.load_all(name, generation, version, 0);
            } else if moved {
                self.read_position(&name);
            }
            self.dirty = true;
        }
    }

    fn publish(&self) {
        let mut built = Vec::new();
        let now = Instant::now();
        for (name, p) in &self.players {
            if !p.loaded {
                continue;
            }
            let suffix = &name[PREFIX.len()..];
            let app = suffix.find(".instance").filter(|i| *i > 0).map_or(suffix, |i| &suffix[..i]);
            built.push((name, p, app, now.saturating_duration_since(p.position_at)));
        }
        let empty = Metadata { length_micros: -1, ..Metadata::default() };
        let joined: Vec<String> =
            built.iter().map(|(_, p, _, _)| p.metadata.as_ref().unwrap_or(&empty).album_artists.join(", ")).collect();
        let sessions: Vec<wire::Session> = built
            .iter()
            .zip(&joined)
            .map(|((name, p, app, age), album_artist)| {
                let m = p.metadata.as_ref().unwrap_or(&empty);
                wire::Session {
                    id: p.id,
                    name,
                    app,
                    identity: &p.identity,
                    title: &m.title,
                    artists: &m.artists,
                    album: &m.album,
                    album_artist,
                    track_number: m.track_number,
                    url: &m.url,
                    track_id: if m.track_id == NO_TRACK { "" } else { &m.track_id },
                    art_url: &m.art_url,
                    status: match p.status.as_str() {
                        "Playing" => 1,
                        "Paused" => 2,
                        _ => 0,
                    },
                    position_millis: p.position_micros / 1000,
                    length_millis: if m.length_micros > 0 { m.length_micros / 1000 } else { -1 },
                    age: *age,
                    rate: p.rate.filter(|r| *r > 0.0).unwrap_or(1.0),
                    shuffle: match p.shuffle {
                        Some(false) => 0,
                        Some(true) => 1,
                        None => 2,
                    },
                    repeat: match p.loop_status.as_deref() {
                        Some("Track") => 1,
                        Some("Playlist") => 2,
                        Some(_) => 0,
                        None => 3,
                    },
                    volume: p.volume.map_or(f64::NAN, |v| v.clamp(0.0, 1.0)),
                    controls: p.controls(),
                }
            })
            .collect();
        self.shared.publish_sessions(wire::sessions(&sessions));
    }

    fn command(&mut self, command: Command) {
        let request = command.request;
        let Some((name, player)) = self.players.iter().find(|(_, p)| p.id == command.session) else {
            self.shared.publish(wire::result(request, wire::RESULT_REFUSED, "the player left the bus"));
            return;
        };
        let name = name.clone();
        let method = match command.control {
            0 => Call::Player("Play"),
            1 => Call::Player("Pause"),
            2 => Call::Player("PlayPause"),
            3 => Call::Player("Stop"),
            4 => Call::Player("Next"),
            5 => Call::Player("Previous"),
            6 => {
                let id = player.metadata.as_ref().map(|m| m.track_id.clone()).unwrap_or_default();
                if id.starts_with('/') && id != NO_TRACK && ObjectPath::try_from(id.as_str()).is_ok() {
                    Call::SetPosition(id, command.position_millis.saturating_mul(1000))
                } else {
                    Call::Seek(command.position_millis.saturating_mul(1000) - player.current_micros())
                }
            }
            9 => Call::Set("Shuffle", Value::Bool(command.on).try_into().unwrap()),
            10 => Call::Set(
                "LoopStatus",
                Value::from(match command.repeat {
                    1 => "Track",
                    2 => "Playlist",
                    _ => "None",
                })
                .try_into()
                .unwrap(),
            ),
            11 => Call::Set("Rate", Value::F64(command.value).try_into().unwrap()),
            12 => Call::Set("Volume", Value::F64(command.value).try_into().unwrap()),
            other => {
                let reason = format!("MPRIS has no control {other}");
                self.shared.publish(wire::result(request, wire::RESULT_UNSUPPORTED, &reason));
                return;
            }
        };
        let shared = self.shared.clone();
        let connection = self.connection.clone();
        let queued = Instant::now();
        let spawned = std::thread::Builder::new().name("orpheus-mpris-call".into()).spawn(move || {
            if queued.elapsed() > COMMAND_LIFETIME {
                shared.publish(wire::result(request, wire::RESULT_FAILED, "timed out"));
                return;
            }
            let destination = Some(name.as_str());
            let reply = match method {
                Call::Player(member) => connection.call_method(destination, OBJECT, Some(PLAYER), member, &()),
                Call::SetPosition(id, micros) => {
                    let path = ObjectPath::try_from(id.as_str()).unwrap();
                    connection.call_method(destination, OBJECT, Some(PLAYER), "SetPosition", &(path, micros))
                }
                Call::Seek(offset) => connection.call_method(destination, OBJECT, Some(PLAYER), "Seek", &(offset)),
                Call::Set(property, value) => {
                    connection.call_method(destination, OBJECT, Some(PROPERTIES), "Set", &(PLAYER, property, value))
                }
            };
            let (status, reason) = match reply {
                Ok(_) => (wire::RESULT_DONE, String::new()),
                Err(zbus::Error::MethodError(error, text, _)) => {
                    let text = text.unwrap_or_default();
                    if error.as_str().ends_with("UnknownMethod") || error.as_str().ends_with("NotSupported") {
                        (wire::RESULT_UNSUPPORTED, text)
                    } else {
                        (wire::RESULT_REFUSED, format!("{}: {text}", error.as_str()))
                    }
                }
                Err(zbus::Error::InputOutput(_)) | Err(zbus::Error::Failure(_)) => {
                    (wire::RESULT_FAILED, "no answer from the player".to_string())
                }
                Err(e) => (wire::RESULT_FAILED, e.to_string()),
            };
            shared.publish(wire::result(request, status, &reason));
        });
        if spawned.is_err() {
            self.shared.publish(wire::result(request, wire::RESULT_FAILED, "no thread for the command"));
        }
    }
}

enum Call {
    Player(&'static str),
    SetPosition(String, i64),
    Seek(i64),
    Set(&'static str, OwnedValue),
}

fn get_all(connection: &Connection, name: &str, interface: &str) -> Option<HashMap<String, OwnedValue>> {
    let reply = connection.call_method(Some(name), OBJECT, Some(PROPERTIES), "GetAll", &(interface)).ok()?;
    reply.body().deserialize().ok()
}
