// SPDX-License-Identifier: MIT

//! The byte layout shared with the Java side. Little endian. A blob is a sequence of records, each starting
//! with a kind byte. Strings are a u32 byte length followed by UTF-8.

use std::time::Duration;

use crate::Command;

pub const KIND_AGE: u8 = 0;
pub const KIND_SESSIONS: u8 = 1;
pub const KIND_RESULT: u8 = 3;
pub const KIND_STATUS: u8 = 4;

pub const RESULT_DONE: u8 = 0;
pub const RESULT_REFUSED: u8 = 1;
pub const RESULT_FAILED: u8 = 2;
pub const RESULT_UNSUPPORTED: u8 = 3;

pub fn text(out: &mut Vec<u8>, value: &str) {
    out.extend((value.len() as u32).to_le_bytes());
    out.extend(value.as_bytes());
}

/// How long ago the sessions record that follows was built.
pub fn age(out: &mut Vec<u8>, elapsed: Duration) {
    out.push(KIND_AGE);
    out.extend((elapsed.as_nanos() as u64).to_le_bytes());
}

pub fn result(request: u64, status: u8, reason: &str) -> Vec<u8> {
    let mut out = vec![KIND_RESULT];
    out.extend(request.to_le_bytes());
    out.push(status);
    text(&mut out, reason);
    out
}

pub fn status(ready: bool, reason: &str) -> Vec<u8> {
    let mut out = vec![KIND_STATUS, if ready { 1 } else { 2 }];
    text(&mut out, reason);
    out
}

/// One player as Java reads it.
pub struct Session<'a> {
    pub id: u32,
    pub name: &'a str,
    pub app: &'a str,
    pub identity: &'a str,
    pub title: &'a str,
    pub artists: &'a [String],
    pub album: &'a str,
    pub album_artist: &'a str,
    pub track_number: i32,
    pub url: &'a str,
    pub track_id: &'a str,
    pub art_url: &'a str,
    /// 0 stopped, 1 playing, 2 paused.
    pub status: u8,
    pub position_millis: i64,
    pub length_millis: i64,
    /// How long before the record was built the position was measured.
    pub age: Duration,
    pub rate: f64,
    /// 0 off, 1 on, 2 unknown.
    pub shuffle: u8,
    /// 0 off, 1 one, 2 all, 3 unknown.
    pub repeat: u8,
    /// NaN when unknown.
    pub volume: f64,
    pub controls: u32,
}

pub fn sessions(sessions: &[Session]) -> Vec<u8> {
    let mut out = vec![KIND_SESSIONS];
    out.extend((sessions.len() as u32).to_le_bytes());
    for s in sessions {
        out.extend(s.id.to_le_bytes());
        for value in [s.name, s.app, s.identity, s.title] {
            text(&mut out, value);
        }
        out.extend((s.artists.len() as u32).to_le_bytes());
        for artist in s.artists {
            text(&mut out, artist);
        }
        for value in [s.album, s.album_artist] {
            text(&mut out, value);
        }
        out.extend(s.track_number.to_le_bytes());
        for value in [s.url, s.track_id, s.art_url] {
            text(&mut out, value);
        }
        out.push(s.status);
        out.extend(s.position_millis.to_le_bytes());
        out.extend(s.length_millis.to_le_bytes());
        out.extend((s.age.as_nanos() as i64).to_le_bytes());
        out.extend(s.rate.to_le_bytes());
        out.push(s.shuffle);
        out.push(s.repeat);
        out.extend(s.volume.to_le_bytes());
        out.extend(s.controls.to_le_bytes());
    }
    out
}

/// Decodes a command: request u64, session u32, control u8, position i64, value f64, on u8, repeat u8.
pub fn command(bytes: &[u8]) -> Option<Command> {
    if bytes.len() != 31 {
        return None;
    }
    Some(Command {
        request: u64::from_le_bytes(bytes[0..8].try_into().ok()?),
        session: u32::from_le_bytes(bytes[8..12].try_into().ok()?),
        control: bytes[12],
        position_millis: i64::from_le_bytes(bytes[13..21].try_into().ok()?),
        value: f64::from_le_bytes(bytes[21..29].try_into().ok()?),
        on: bytes[29] != 0,
        repeat: bytes[30],
    })
}
