// SPDX-License-Identifier: MIT

//! The byte layout shared with the Java side. Little endian. A blob is a sequence of records, each starting
//! with a kind byte. Strings are a u32 byte length followed by UTF-8.

use std::time::Duration;

use crate::Command;

pub const KIND_AGE: u8 = 0;
pub const KIND_SESSIONS: u8 = 1;
pub const KIND_THUMBNAIL: u8 = 2;
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

pub fn thumbnail(key: u64, bytes: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len() + 13);
    out.push(KIND_THUMBNAIL);
    out.extend(key.to_le_bytes());
    out.extend((bytes.len() as u32).to_le_bytes());
    out.extend(bytes);
    out
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
