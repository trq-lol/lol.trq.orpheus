// SPDX-License-Identifier: MIT

//! A stand-in media player for tests. It publishes a Windows media session, takes its script from stdin and
//! prints every command the system delivers to stdout, one per line.
//!
//! stdin, tab separated:
//!   track <title> <artist> <album> <album artist> <duration ms> <cover RRGGBB or -> <music|video>
//!   state <playing|paused|stopped>
//!   position <ms>
//!   controls <on|off>      (off disables next, previous, fast-forward and rewind)
//!   quit
//! stdout: "ready", then "done <verb>" after each script line and "command <name> [value]" for each command.

#[cfg(not(windows))]
fn main() {
    eprintln!("this publisher only runs on Windows");
    std::process::exit(2);
}

#[cfg(windows)]
fn main() -> windows::core::Result<()> {
    publisher::run()
}

#[cfg(windows)]
mod publisher {
    use std::io::{BufRead, Write};
    use std::sync::mpsc;
    use std::time::Duration;

    use windows::Foundation::{TimeSpan, TypedEventHandler};
    use windows::Media::{
        AutoRepeatModeChangeRequestedEventArgs, MediaPlaybackAutoRepeatMode, MediaPlaybackStatus, MediaPlaybackType,
        PlaybackPositionChangeRequestedEventArgs, PlaybackRateChangeRequestedEventArgs,
        ShuffleEnabledChangeRequestedEventArgs, SystemMediaTransportControls, SystemMediaTransportControlsButton,
        SystemMediaTransportControlsButtonPressedEventArgs, SystemMediaTransportControlsTimelineProperties,
    };
    use windows::Storage::Streams::{DataWriter, InMemoryRandomAccessStream, RandomAccessStreamReference};
    use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::System::WinRT::{ISystemMediaTransportControlsInterop, RO_INIT_MULTITHREADED, RoInitialize};
    use windows::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DispatchMessageW, MSG, PM_REMOVE, PeekMessageW, RegisterClassW,
        TranslateMessage, WINDOW_EX_STYLE, WNDCLASSW, WS_OVERLAPPEDWINDOW,
    };
    use windows::core::{HSTRING, Ref, Result, factory, w};

    fn say(line: &str) {
        let mut out = std::io::stdout().lock();
        let _ = writeln!(out, "{line}");
        let _ = out.flush();
    }

    fn ticks(millis: i64) -> TimeSpan {
        TimeSpan { Duration: millis * 10_000 }
    }

    unsafe extern "system" fn window_procedure(window: HWND, message: u32, w: WPARAM, l: LPARAM) -> LRESULT {
        unsafe { DefWindowProcW(window, message, w, l) }
    }

    /// A flat-colour PNG, written without a compression library.
    fn png(side: u32, rgb: [u8; 3]) -> Vec<u8> {
        fn crc(data: &[u8]) -> u32 {
            let mut c = 0xFFFF_FFFFu32;
            for &b in data {
                c ^= b as u32;
                for _ in 0..8 {
                    c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
                }
            }
            !c
        }
        fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
            out.extend((data.len() as u32).to_be_bytes());
            let mut body = kind.to_vec();
            body.extend(data);
            out.extend(&body);
            out.extend(crc(&body).to_be_bytes());
        }
        let mut raw = Vec::new();
        for _ in 0..side {
            raw.push(0);
            for _ in 0..side {
                raw.extend(rgb);
            }
        }
        let mut deflated = vec![0x78, 0x01];
        let mut blocks = raw.chunks(65535).peekable();
        while let Some(block) = blocks.next() {
            deflated.push(if blocks.peek().is_none() { 1 } else { 0 });
            deflated.extend((block.len() as u16).to_le_bytes());
            deflated.extend((!(block.len() as u16)).to_le_bytes());
            deflated.extend(block);
        }
        let (mut a, mut b) = (1u32, 0u32);
        for &x in &raw {
            a = (a + x as u32) % 65521;
            b = (b + a) % 65521;
        }
        deflated.extend(((b << 16) | a).to_be_bytes());
        let mut out = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        let mut header = Vec::new();
        header.extend(side.to_be_bytes());
        header.extend(side.to_be_bytes());
        header.extend([8, 2, 0, 0, 0]);
        chunk(&mut out, b"IHDR", &header);
        chunk(&mut out, b"IDAT", &deflated);
        chunk(&mut out, b"IEND", &[]);
        out
    }

    fn set_timeline(controls: &SystemMediaTransportControls, position: i64, duration: i64) -> Result<()> {
        let timeline = SystemMediaTransportControlsTimelineProperties::new()?;
        timeline.SetStartTime(ticks(0))?;
        timeline.SetEndTime(ticks(duration))?;
        timeline.SetMinSeekTime(ticks(0))?;
        timeline.SetMaxSeekTime(ticks(duration))?;
        timeline.SetPosition(ticks(position))?;
        controls.UpdateTimelineProperties(&timeline)
    }

    fn set_track(controls: &SystemMediaTransportControls, fields: &[&str]) -> Result<()> {
        let updater = controls.DisplayUpdater()?;
        updater.ClearAll()?;
        let video = fields.get(6) == Some(&"video");
        updater.SetType(if video { MediaPlaybackType::Video } else { MediaPlaybackType::Music })?;
        let field = |index: usize| HSTRING::from(*fields.get(index).unwrap_or(&""));
        if video {
            updater.VideoProperties()?.SetTitle(&field(0))?;
            updater.VideoProperties()?.SetSubtitle(&field(1))?;
        } else {
            let music = updater.MusicProperties()?;
            music.SetTitle(&field(0))?;
            music.SetArtist(&field(1))?;
            music.SetAlbumTitle(&field(2))?;
            music.SetAlbumArtist(&field(3))?;
        }
        if let Some(colour) = fields.get(5).filter(|c| c.len() == 6)
            && let Ok(value) = u32::from_str_radix(colour, 16)
        {
            let stream = InMemoryRandomAccessStream::new()?;
            let writer = DataWriter::CreateDataWriter(&stream)?;
            writer.WriteBytes(&png(64, [(value >> 16) as u8, (value >> 8) as u8, value as u8]))?;
            writer.StoreAsync()?.join()?;
            writer.DetachStream()?;
            stream.Seek(0)?;
            updater.SetThumbnail(&RandomAccessStreamReference::CreateFromStream(&stream)?)?;
        }
        updater.Update()?;
        set_timeline(controls, 0, fields.get(4).and_then(|d| d.parse().ok()).unwrap_or(0))
    }

    fn enable(controls: &SystemMediaTransportControls, all: bool) -> Result<()> {
        controls.SetIsPlayEnabled(true)?;
        controls.SetIsPauseEnabled(true)?;
        controls.SetIsStopEnabled(true)?;
        controls.SetIsNextEnabled(all)?;
        controls.SetIsPreviousEnabled(all)?;
        controls.SetIsFastForwardEnabled(all)?;
        controls.SetIsRewindEnabled(all)?;
        Ok(())
    }

    fn listen(controls: &SystemMediaTransportControls) -> Result<()> {
        controls.ButtonPressed(&TypedEventHandler::new(
            |sender: Ref<SystemMediaTransportControls>, args: Ref<SystemMediaTransportControlsButtonPressedEventArgs>| {
                let (Some(sender), Some(args)) = (sender.as_ref(), args.as_ref()) else { return Ok(()) };
                let button = args.Button()?;
                let name = match button {
                    SystemMediaTransportControlsButton::Play => {
                        sender.SetPlaybackStatus(MediaPlaybackStatus::Playing)?;
                        "play"
                    }
                    SystemMediaTransportControlsButton::Pause => {
                        sender.SetPlaybackStatus(MediaPlaybackStatus::Paused)?;
                        "pause"
                    }
                    SystemMediaTransportControlsButton::Stop => {
                        sender.SetPlaybackStatus(MediaPlaybackStatus::Stopped)?;
                        "stop"
                    }
                    SystemMediaTransportControlsButton::Next => "next",
                    SystemMediaTransportControlsButton::Previous => "previous",
                    SystemMediaTransportControlsButton::FastForward => "fast-forward",
                    SystemMediaTransportControlsButton::Rewind => "rewind",
                    _ => "other",
                };
                say(&format!("command {name}"));
                Ok(())
            },
        ))?;
        controls.PlaybackPositionChangeRequested(&TypedEventHandler::new(
            |_, args: Ref<PlaybackPositionChangeRequestedEventArgs>| {
                if let Some(args) = args.as_ref() {
                    say(&format!("command seek {}", args.RequestedPlaybackPosition()?.Duration / 10_000));
                }
                Ok(())
            },
        ))?;
        controls.ShuffleEnabledChangeRequested(&TypedEventHandler::new(
            |sender: Ref<SystemMediaTransportControls>, args: Ref<ShuffleEnabledChangeRequestedEventArgs>| {
                let (Some(sender), Some(args)) = (sender.as_ref(), args.as_ref()) else { return Ok(()) };
                let on = args.RequestedShuffleEnabled()?;
                sender.SetShuffleEnabled(on)?;
                say(&format!("command shuffle {on}"));
                Ok(())
            },
        ))?;
        controls.AutoRepeatModeChangeRequested(&TypedEventHandler::new(
            |sender: Ref<SystemMediaTransportControls>, args: Ref<AutoRepeatModeChangeRequestedEventArgs>| {
                let (Some(sender), Some(args)) = (sender.as_ref(), args.as_ref()) else { return Ok(()) };
                let mode = args.RequestedAutoRepeatMode()?;
                sender.SetAutoRepeatMode(mode)?;
                say(&format!("command repeat {}", mode.0));
                Ok(())
            },
        ))?;
        controls.PlaybackRateChangeRequested(&TypedEventHandler::new(
            |sender: Ref<SystemMediaTransportControls>, args: Ref<PlaybackRateChangeRequestedEventArgs>| {
                let (Some(sender), Some(args)) = (sender.as_ref(), args.as_ref()) else { return Ok(()) };
                let rate = args.RequestedPlaybackRate()?;
                sender.SetPlaybackRate(rate)?;
                say(&format!("command rate {rate}"));
                Ok(())
            },
        ))?;
        Ok(())
    }

    pub fn run() -> Result<()> {
        let controls: SystemMediaTransportControls = unsafe {
            RoInitialize(RO_INIT_MULTITHREADED)?;
            let instance = GetModuleHandleW(None)?;
            let class = w!("orpheus_test_publisher");
            RegisterClassW(&WNDCLASSW {
                lpfnWndProc: Some(window_procedure),
                hInstance: instance.into(),
                lpszClassName: class,
                ..Default::default()
            });
            let window = CreateWindowExW(
                WINDOW_EX_STYLE(0),
                class,
                w!("orpheus test publisher"),
                WS_OVERLAPPEDWINDOW,
                0,
                0,
                100,
                100,
                None,
                None,
                Some(instance.into()),
                None,
            )?;
            factory::<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>()?.GetForWindow(window)?
        };
        controls.SetIsEnabled(true)?;
        enable(&controls, true)?;
        controls.SetShuffleEnabled(false)?;
        controls.SetAutoRepeatMode(MediaPlaybackAutoRepeatMode::None)?;
        controls.SetPlaybackRate(1.0)?;
        listen(&controls)?;

        let (sender, lines) = mpsc::channel::<String>();
        std::thread::spawn(move || {
            for line in std::io::stdin().lock().lines().map_while(|l| l.ok()) {
                if sender.send(line).is_err() {
                    return;
                }
            }
            let _ = sender.send("quit".into());
        });

        say("ready");
        let mut duration = 0i64;
        let mut message = MSG::default();
        loop {
            unsafe {
                while PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {
                    let _ = TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            }
            match lines.recv_timeout(Duration::from_millis(5)) {
                Ok(line) => {
                    let line = line.trim_end_matches(['\r', '\n']);
                    let (verb, rest) = line.split_once('\t').unwrap_or((line, ""));
                    let fields: Vec<&str> = rest.split('\t').collect();
                    match verb {
                        "track" => {
                            duration = fields.get(4).and_then(|d| d.parse().ok()).unwrap_or(0);
                            set_track(&controls, &fields)?;
                        }
                        "state" => controls.SetPlaybackStatus(match fields[0] {
                            "playing" => MediaPlaybackStatus::Playing,
                            "paused" => MediaPlaybackStatus::Paused,
                            _ => MediaPlaybackStatus::Stopped,
                        })?,
                        "position" => set_timeline(&controls, fields[0].parse().unwrap_or(0), duration)?,
                        "controls" => enable(&controls, fields[0] == "on")?,
                        "quit" => return Ok(()),
                        _ => {}
                    }
                    say(&format!("done {verb}"));
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) => return Ok(()),
            }
        }
    }
}
