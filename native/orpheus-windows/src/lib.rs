// SPDX-License-Identifier: MIT

//! Windows media sessions for the JVM.
//!
//! One worker thread per handle owns every WinRT object. Event handlers only raise flags. The JVM never
//! receives a call from here: a Java thread blocks in `await` and gets byte blobs back.

#![cfg(windows)]

mod jni;
mod sessions;
mod wire;

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// Bumped whenever the blob layout or an operation changes. The Java side refuses a mismatch.
pub const ABI: u32 = 1;

pub const OP_ABI: i32 = 0;
pub const OP_OPEN: i32 = 1;
pub const OP_AWAIT: i32 = 2;
pub const OP_COMMAND: i32 = 3;
pub const OP_CLOSE: i32 = 4;

/// A command from Java, decoded.
pub struct Command {
    pub request: u64,
    pub session: u32,
    pub control: u8,
    pub position_millis: i64,
    pub value: f64,
    pub on: bool,
    pub repeat: u8,
}

#[derive(Default)]
struct Inbox {
    stop: bool,
    relist: bool,
    dirty: Vec<u32>,
    commands: VecDeque<Command>,
}

#[derive(Default)]
struct Outbox {
    closed: bool,
    sessions: Option<(Vec<u8>, Instant)>,
    queued: VecDeque<Vec<u8>>,
}

/// State shared between the worker, the WinRT handlers and the Java threads.
pub struct Shared {
    inbox: Mutex<Inbox>,
    inbox_ready: Condvar,
    outbox: Mutex<Outbox>,
    outbox_ready: Condvar,
}

impl Shared {
    pub fn mark_relist(&self) {
        self.inbox.lock().unwrap().relist = true;
        self.inbox_ready.notify_one();
    }

    pub fn mark_dirty(&self, session: u32) {
        let mut inbox = self.inbox.lock().unwrap();
        if !inbox.dirty.contains(&session) {
            inbox.dirty.push(session);
        }
        drop(inbox);
        self.inbox_ready.notify_one();
    }

    pub fn publish_sessions(&self, blob: Vec<u8>) {
        self.outbox.lock().unwrap().sessions = Some((blob, Instant::now()));
        self.outbox_ready.notify_all();
    }

    pub fn publish(&self, record: Vec<u8>) {
        self.outbox.lock().unwrap().queued.push_back(record);
        self.outbox_ready.notify_all();
    }
}

struct Instance {
    shared: Arc<Shared>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

fn table() -> &'static Mutex<HashMap<u64, Arc<Instance>>> {
    static TABLE: OnceLock<Mutex<HashMap<u64, Arc<Instance>>>> = OnceLock::new();
    TABLE.get_or_init(Default::default)
}

fn find(handle: u64) -> Option<Arc<Instance>> {
    table().lock().unwrap().get(&handle).cloned()
}

pub fn open() -> u64 {
    static NEXT: AtomicU64 = AtomicU64::new(1);
    let shared = Arc::new(Shared {
        inbox: Mutex::default(),
        inbox_ready: Condvar::new(),
        outbox: Mutex::default(),
        outbox_ready: Condvar::new(),
    });
    let for_worker = shared.clone();
    let worker = std::thread::Builder::new()
        .name("orpheus-winrt".into())
        .spawn(move || {
            let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| sessions::run(&for_worker)));
            let reason = match outcome {
                Ok(Ok(())) => None,
                Ok(Err(error)) => Some(error.message()),
                Err(_) => Some("the media session worker stopped unexpectedly".to_string()),
            };
            if let Some(reason) = reason {
                for_worker.publish(wire::status(false, &reason));
            }
        })
        .ok();
    if worker.is_none() {
        shared.publish(wire::status(false, "the media session worker thread could not be started"));
    }
    let handle = NEXT.fetch_add(1, Ordering::Relaxed);
    table().lock().unwrap().insert(handle, Arc::new(Instance { shared, worker: Mutex::new(worker) }));
    handle
}

/// Blocks until there is something to report, the timeout passes (empty blob) or the handle closes (None).
pub fn await_blob(handle: u64, timeout: Duration) -> Option<Vec<u8>> {
    let instance = find(handle)?;
    let shared = &instance.shared;
    let deadline = Instant::now() + timeout;
    let mut outbox = shared.outbox.lock().unwrap();
    loop {
        if outbox.closed {
            return None;
        }
        if outbox.sessions.is_some() || !outbox.queued.is_empty() {
            let mut blob = Vec::new();
            // Thumbnails first: the sessions record refers to them by key.
            while let Some(record) = outbox.queued.pop_front() {
                blob.extend(record);
            }
            if let Some((sessions, built)) = outbox.sessions.take() {
                wire::age(&mut blob, built.elapsed());
                blob.extend(sessions);
            }
            return Some(blob);
        }
        let now = Instant::now();
        if now >= deadline {
            return Some(Vec::new());
        }
        outbox = shared.outbox_ready.wait_timeout(outbox, deadline - now).unwrap().0;
    }
}

pub fn command(handle: u64, command: Command) -> bool {
    let Some(instance) = find(handle) else {
        return false;
    };
    instance.shared.inbox.lock().unwrap().commands.push_back(command);
    instance.shared.inbox_ready.notify_one();
    true
}

/// Stops the worker, wakes any waiting Java thread and waits for the worker to release its WinRT objects.
pub fn close(handle: u64) {
    let Some(instance) = table().lock().unwrap().remove(&handle) else {
        return;
    };
    instance.shared.inbox.lock().unwrap().stop = true;
    instance.shared.inbox_ready.notify_all();
    instance.shared.outbox.lock().unwrap().closed = true;
    instance.shared.outbox_ready.notify_all();
    if let Some(worker) = instance.worker.lock().unwrap().take() {
        let _ = worker.join();
    }
}
