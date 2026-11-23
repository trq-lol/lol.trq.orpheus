// SPDX-License-Identifier: MIT

//! The worker: lists sessions, re-reads the ones whose handlers fired, runs commands, and serializes state.

use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use windows::Foundation::TypedEventHandler;
use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSession as Session, GlobalSystemMediaTransportControlsSessionManager as Manager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
};
use windows::Media::{MediaPlaybackAutoRepeatMode, MediaPlaybackType};
use windows::Storage::Streams::DataReader;
use windows::Win32::Foundation::{E_ABORT, RPC_E_CHANGED_MODE};
use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize, RoUninitialize};
use windows::core::{Error, HRESULT, Result, RuntimeType};
use windows_future::{AsyncStatus, IAsyncOperation};

use crate::{Command, Shared, wire};

const CALL_TIMEOUT: Duration = Duration::from_secs(3);
const RELIST_INTERVAL: Duration = Duration::from_secs(1);
const MAX_THUMBNAIL_BYTES: u64 = 16 << 20;
const MAX_TEXT_BYTES: usize = 4096;
/// Windows FILETIME ticks (100 ns since 1601) at the Unix epoch.
const EPOCH_TICKS: i64 = 116_444_736_000_000_000;
const ERROR_TIMEOUT: HRESULT = HRESULT(0x800705B4u32 as i32);

// Bit positions match the ordinals of the Java `Control` enum.
const PLAY: u32 = 1 << 0;
const PAUSE: u32 = 1 << 1;
const TOGGLE: u32 = 1 << 2;
const STOP: u32 = 1 << 3;
const NEXT: u32 = 1 << 4;
const PREVIOUS: u32 = 1 << 5;
const SEEK: u32 = 1 << 6;
const FAST_FORWARD: u32 = 1 << 7;
const REWIND: u32 = 1 << 8;
const SHUFFLE: u32 = 1 << 9;
const REPEAT: u32 = 1 << 10;
const RATE: u32 = 1 << 11;

/// Waits for a WinRT operation without ever blocking for good on a frozen application.
fn wait<T: RuntimeType + 'static>(operation: IAsyncOperation<T>) -> Result<T> {
    let started = Instant::now();
    loop {
        if operation.Status()? != AsyncStatus::Started {
            return operation.GetResults();
        }
        let elapsed = started.elapsed();
        if elapsed > CALL_TIMEOUT {
            let _ = operation.Cancel();
            return Err(Error::from(ERROR_TIMEOUT));
        }
        if elapsed < Duration::from_micros(300) {
            std::thread::yield_now();
        } else {
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}

#[derive(Default, Clone, PartialEq)]
struct State {
    title: String,
    artist: String,
    album: String,
    album_artist: String,
    track_number: i32,
    kind: u8,
    status: u8,
    position_millis: i64,
    duration_millis: i64,
    updated_ticks: i64,
    rate: f64,
    shuffle: u8,
    repeat: u8,
    controls: u32,
    thumbnail: u64,
    start_ticks: i64,
}

struct Tracked {
    id: u32,
    app: String,
    session: Session,
    tokens: [i64; 3],
    state: State,
}

impl Tracked {
    fn release(&self) {
        let _ = self.session.RemoveMediaPropertiesChanged(self.tokens[0]);
        let _ = self.session.RemovePlaybackInfoChanged(self.tokens[1]);
        let _ = self.session.RemoveTimelinePropertiesChanged(self.tokens[2]);
    }
}

fn cap(text: String) -> String {
    if text.len() <= MAX_TEXT_BYTES {
        return text;
    }
    let mut end = MAX_TEXT_BYTES;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    text[..end].to_string()
}

fn fnv(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for &b in bytes {
        hash = (hash ^ b as u64).wrapping_mul(0x100000001b3);
    }
    hash.max(1)
}

fn read_thumbnail(
    session_properties: &windows::Media::Control::GlobalSystemMediaTransportControlsSessionMediaProperties,
) -> Option<Vec<u8>> {
    let reference = session_properties.Thumbnail().ok()?;
    let stream = wait(reference.OpenReadAsync().ok()?).ok()?;
    let size = stream.Size().ok()?;
    if size == 0 || size > MAX_THUMBNAIL_BYTES {
        return None;
    }
    let reader = DataReader::CreateDataReader(&stream).ok()?;
    let loaded = wait(reader.LoadAsync(size as u32).ok()?).ok()?;
    let mut bytes = vec![0u8; loaded as usize];
    reader.ReadBytes(&mut bytes).ok()?;
    let _ = reader.Close();
    let _ = stream.Close();
    Some(bytes)
}

/// Reads the parts of a session. Each part keeps its previous value when its call fails.
fn refresh(tracked: &mut Tracked, shared: &Shared, media: bool) -> Result<()> {
    let mut state = tracked.state.clone();
    let session = &tracked.session;
    let mut first_error = None;

    if media {
        match session.TryGetMediaPropertiesAsync().and_then(wait) {
            Ok(properties) => {
                state.title = cap(properties.Title().map(|s| s.to_string()).unwrap_or_default());
                state.artist = cap(properties.Artist().map(|s| s.to_string()).unwrap_or_default());
                state.album = cap(properties.AlbumTitle().map(|s| s.to_string()).unwrap_or_default());
                state.album_artist = cap(properties.AlbumArtist().map(|s| s.to_string()).unwrap_or_default());
                state.track_number = properties.TrackNumber().unwrap_or(0);
                state.kind = match properties.PlaybackType().and_then(|t| t.Value()) {
                    Ok(MediaPlaybackType::Music) => 1,
                    Ok(MediaPlaybackType::Video) => 2,
                    Ok(MediaPlaybackType::Image) => 3,
                    _ => 0,
                };
                match read_thumbnail(&properties) {
                    Some(bytes) => {
                        let key = fnv(&bytes);
                        if key != state.thumbnail {
                            shared.publish(wire::thumbnail(key, &bytes));
                            state.thumbnail = key;
                        }
                    }
                    None => state.thumbnail = 0,
                }
            }
            Err(error) => first_error = Some(error),
        }
    }

    match session.GetTimelineProperties() {
        Ok(timeline) => {
            let start = timeline.StartTime().map(|t| t.Duration).unwrap_or(0);
            let end = timeline.EndTime().map(|t| t.Duration).unwrap_or(0);
            let position = timeline.Position().map(|t| t.Duration).unwrap_or(0);
            state.start_ticks = start;
            state.position_millis = ((position - start) / 10_000).max(0);
            state.duration_millis = if end > start { (end - start) / 10_000 } else { -1 };
            state.updated_ticks = timeline.LastUpdatedTime().map(|t| t.UniversalTime).unwrap_or(0);
        }
        Err(error) => first_error = first_error.or(Some(error)),
    }

    match session.GetPlaybackInfo() {
        Ok(info) => {
            state.status = match info.PlaybackStatus() {
                Ok(Status::Playing) => 1,
                Ok(Status::Paused) => 2,
                Ok(Status::Changing) => 3,
                _ => 0,
            };
            state.rate = info.PlaybackRate().and_then(|v| v.Value()).unwrap_or(f64::NAN);
            state.shuffle = match info.IsShuffleActive().and_then(|v| v.Value()) {
                Ok(true) => 1,
                Ok(false) => 0,
                Err(_) => 2,
            };
            state.repeat = match info.AutoRepeatMode().and_then(|v| v.Value()) {
                Ok(MediaPlaybackAutoRepeatMode::None) => 0,
                Ok(MediaPlaybackAutoRepeatMode::Track) => 1,
                Ok(MediaPlaybackAutoRepeatMode::List) => 2,
                _ => 3,
            };
            if let Ok(controls) = info.Controls() {
                let flag = |enabled: Result<bool>, bit: u32| {
                    if enabled.unwrap_or(false) { bit } else { 0 }
                };
                state.controls = flag(controls.IsPlayEnabled(), PLAY)
                    | flag(controls.IsPauseEnabled(), PAUSE)
                    | flag(controls.IsPlayPauseToggleEnabled(), TOGGLE)
                    | flag(controls.IsStopEnabled(), STOP)
                    | flag(controls.IsNextEnabled(), NEXT)
                    | flag(controls.IsPreviousEnabled(), PREVIOUS)
                    | flag(controls.IsPlaybackPositionEnabled(), SEEK)
                    | flag(controls.IsFastForwardEnabled(), FAST_FORWARD)
                    | flag(controls.IsRewindEnabled(), REWIND)
                    | flag(controls.IsShuffleEnabled(), SHUFFLE)
                    | flag(controls.IsRepeatEnabled(), REPEAT)
                    | flag(controls.IsPlaybackRateEnabled(), RATE);
            }
        }
        Err(error) => first_error = first_error.or(Some(error)),
    }

    tracked.state = state;
    first_error.map_or(Ok(()), Err)
}

fn serialize(sessions: &[Tracked]) -> Vec<u8> {
    let now_ticks =
        SystemTime::now().duration_since(UNIX_EPOCH).map(|d| (d.as_nanos() / 100) as i64 + EPOCH_TICKS).unwrap_or(0);
    let mut out = vec![wire::KIND_SESSIONS];
    out.extend((sessions.len() as u32).to_le_bytes());
    for tracked in sessions {
        let s = &tracked.state;
        out.extend(tracked.id.to_le_bytes());
        wire::text(&mut out, &tracked.app);
        wire::text(&mut out, &s.title);
        wire::text(&mut out, &s.artist);
        wire::text(&mut out, &s.album);
        wire::text(&mut out, &s.album_artist);
        out.extend(s.track_number.to_le_bytes());
        out.push(s.kind);
        out.push(s.status);
        out.extend(s.position_millis.to_le_bytes());
        out.extend(s.duration_millis.to_le_bytes());
        let age_millis =
            if s.updated_ticks > EPOCH_TICKS { ((now_ticks - s.updated_ticks) / 10_000).max(0) } else { 0 };
        out.extend(age_millis.to_le_bytes());
        out.extend(s.rate.to_le_bytes());
        out.push(s.shuffle);
        out.push(s.repeat);
        out.extend(s.controls.to_le_bytes());
        out.extend(s.thumbnail.to_le_bytes());
    }
    out
}

fn execute(tracked: &Tracked, command: &Command) -> Vec<u8> {
    let session = &tracked.session;
    let operation = match command.control {
        0 => session.TryPlayAsync(),
        1 => session.TryPauseAsync(),
        2 => session.TryTogglePlayPauseAsync(),
        3 => session.TryStopAsync(),
        4 => session.TrySkipNextAsync(),
        5 => session.TrySkipPreviousAsync(),
        6 => session.TryChangePlaybackPositionAsync(
            command.position_millis.saturating_mul(10_000).saturating_add(tracked.state.start_ticks),
        ),
        7 => session.TryFastForwardAsync(),
        8 => session.TryRewindAsync(),
        9 => session.TryChangeShuffleActiveAsync(command.on),
        10 => session.TryChangeAutoRepeatModeAsync(match command.repeat {
            1 => MediaPlaybackAutoRepeatMode::Track,
            2 => MediaPlaybackAutoRepeatMode::List,
            _ => MediaPlaybackAutoRepeatMode::None,
        }),
        11 => session.TryChangePlaybackRateAsync(command.value),
        _ => {
            return wire::result(
                command.request,
                wire::RESULT_UNSUPPORTED,
                "Windows media sessions have no such command",
            );
        }
    };
    match operation.and_then(wait) {
        Ok(true) => wire::result(command.request, wire::RESULT_DONE, ""),
        Ok(false) => wire::result(command.request, wire::RESULT_REFUSED, "the application did not accept the command"),
        Err(error) => wire::result(command.request, wire::RESULT_FAILED, &error.message()),
    }
}

struct Apartment(bool);

impl Drop for Apartment {
    fn drop(&mut self) {
        if self.0 {
            unsafe { RoUninitialize() };
        }
    }
}

pub fn run(shared: &Arc<Shared>) -> Result<()> {
    let _apartment = match unsafe { RoInitialize(RO_INIT_MULTITHREADED) } {
        Ok(()) => Apartment(true),
        Err(error) if error.code() == RPC_E_CHANGED_MODE => Apartment(false),
        Err(error) => return Err(error),
    };
    let manager = wait(Manager::RequestAsync()?)?;
    let for_handler = shared.clone();
    let sessions_token = manager.SessionsChanged(&TypedEventHandler::new(move |_, _| {
        for_handler.mark_relist();
        Ok(())
    }))?;
    shared.publish(wire::status(true, ""));

    let mut sessions: Vec<Tracked> = Vec::new();
    let mut next_id = 1u32;
    let mut relist = true;
    let mut last_relist: Option<Instant> = None;
    let mut relist_pending = false;

    let outcome = loop {
        let mut dirty: Vec<u32> = Vec::new();
        let mut commands = Vec::new();
        {
            let mut inbox = shared.inbox.lock().unwrap();
            loop {
                if inbox.stop {
                    break;
                }
                if inbox.relist || !inbox.dirty.is_empty() || !inbox.commands.is_empty() || relist {
                    break;
                }
                if relist_pending {
                    let wait_for = RELIST_INTERVAL.saturating_sub(last_relist.map_or(RELIST_INTERVAL, |t| t.elapsed()));
                    if wait_for.is_zero() {
                        break;
                    }
                    inbox = shared.inbox_ready.wait_timeout(inbox, wait_for).unwrap().0;
                } else {
                    inbox = shared.inbox_ready.wait(inbox).unwrap();
                }
            }
            if inbox.stop {
                break Ok(());
            }
            relist |= std::mem::take(&mut inbox.relist);
            dirty.append(&mut inbox.dirty);
            commands.extend(inbox.commands.drain(..));
        }
        // A listing asked for by the system runs at once. One asked for because a call failed waits its turn, so a
        // session that keeps failing cannot spin this thread.
        let retry = relist_pending && last_relist.is_none_or(|t| t.elapsed() >= RELIST_INTERVAL);
        if relist || retry {
            {
                relist = false;
                relist_pending = false;
                last_relist = Some(Instant::now());
                let listed = match manager.GetSessions() {
                    Ok(listed) => listed,
                    Err(error) if error.code() == E_ABORT => break Err(error),
                    Err(_) => {
                        relist_pending = true;
                        continue;
                    }
                };
                let mut kept: Vec<Tracked> = Vec::new();
                for session in &listed {
                    let app = cap(session.SourceAppUserModelId().map(|s| s.to_string()).unwrap_or_default());
                    // The same application keeps its id across listings, but always gets the fresh session object:
                    // the old one may belong to an instance that has exited.
                    let previous = sessions.iter().position(|t| t.app == app).map(|index| sessions.remove(index));
                    let id = match &previous {
                        Some(old) => {
                            old.release();
                            old.id
                        }
                        None => {
                            next_id += 1;
                            next_id - 1
                        }
                    };
                    let (media, playback, timeline) = (shared.clone(), shared.clone(), shared.clone());
                    let tokens = [
                        session
                            .MediaPropertiesChanged(&TypedEventHandler::new(move |_, _| {
                                media.mark_dirty(id | MEDIA_FLAG);
                                Ok(())
                            }))
                            .unwrap_or(0),
                        session
                            .PlaybackInfoChanged(&TypedEventHandler::new(move |_, _| {
                                playback.mark_dirty(id);
                                Ok(())
                            }))
                            .unwrap_or(0),
                        session
                            .TimelinePropertiesChanged(&TypedEventHandler::new(move |_, _| {
                                timeline.mark_dirty(id);
                                Ok(())
                            }))
                            .unwrap_or(0),
                    ];
                    let state = previous.map(|old| old.state).unwrap_or_default();
                    let mut tracked = Tracked { id, app, session, tokens, state };
                    if refresh(&mut tracked, shared, true).is_err() {
                        relist_pending = true;
                    }
                    kept.push(tracked);
                }
                for gone in &sessions {
                    gone.release();
                }
                sessions = kept;
            }
        }

        for flagged in dirty {
            let id = flagged & !MEDIA_FLAG;
            if let Some(tracked) = sessions.iter_mut().find(|t| t.id == id)
                && refresh(tracked, shared, flagged & MEDIA_FLAG != 0).is_err()
            {
                relist_pending = true;
            }
        }

        for command in commands {
            let record = match sessions.iter().find(|t| t.id == command.session) {
                Some(tracked) => execute(tracked, &command),
                None => wire::result(command.request, wire::RESULT_REFUSED, "the session is gone"),
            };
            shared.publish(record);
        }

        shared.publish_sessions(serialize(&sessions));
    };

    for tracked in &sessions {
        tracked.release();
    }
    let _ = manager.RemoveSessionsChanged(sessions_token);
    outcome
}

/// Set on a dirty id when the media properties (text, thumbnail) need re-reading too.
const MEDIA_FLAG: u32 = 1 << 31;
