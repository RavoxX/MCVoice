#!/usr/bin/env python3
"""Create a disposable, instrumented Rust source copy. Never use it for deployment.

Times Hub lock acquisition/hold and counts allocations inside relay_voice only.
Run the resulting binary with compare-rust.py; PROFILE JSON is logged at shutdown.
The uninstrumented binaries must be used for end-to-end performance comparisons.
"""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("destination", type=Path)
args = p.parse_args()
shutil.copytree(ROOT / "backend/rust", args.destination, ignore=shutil.ignore_patterns("target"))
server = args.destination / "src/server"
for name in ("udp.rs", "mod.rs", "control.rs", "groups.rs"):
    file = server / name
    source = file.read_text()
    prefix = "profile" if name == "mod.rs" else "super::profile"
    source = source.replace("self.hub.read().unwrap()", f"{prefix}::read(&self.hub, 0)")
    source = source.replace("self.hub.write().unwrap()", f"{prefix}::write(&self.hub, 1)")
    if name == "udp.rs":
        start = source.index("    fn relay_voice(")
        source = source[:start] + source[start:].replace(f"{prefix}::read(&self.hub, 0)", f"{prefix}::read(&self.hub, 2)")
        start = source.index("        let now_ms = self.now_ms();", start)
        source = source[:start] + "        let _allocations = super::profile::Allocations::start();\n" + source[start:]
    if name == "mod.rs":
        source = "pub mod profile;\n" + source
        source = source.replace("//! Control service + voice relay.", "// Control service + voice relay.")
        start = source.index("    pub(crate) fn presence_tick(")
        end = source.index("    pub(crate) fn update_jitter_gauge", start)
        source = source[:start] + source[start:end].replace(f"{prefix}::write(&self.hub, 1)", f"{prefix}::write(&self.hub, 3)") + source[end:]
    file.write_text(source)
main = args.destination / "src/main.rs"
source = main.read_text()
last = source.rfind("}")
main.write_text(source[:last] + "    mcvoice_backend::server::profile::report();\n" + source[last:])
(server / "profile.rs").write_text(r'''
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::ops::{Deref, DerefMut};
use std::sync::{RwLock, RwLockReadGuard, RwLockWriteGuard};
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::time::Instant;

struct Histogram { count: AtomicU64, sum: AtomicU64, max: AtomicU64, buckets: [AtomicU64; 32] }
impl Histogram {
    const fn new() -> Self { Self { count: AtomicU64::new(0), sum: AtomicU64::new(0), max: AtomicU64::new(0), buckets: [const { AtomicU64::new(0) }; 32] } }
    fn observe(&self, ns: u64) {
        self.count.fetch_add(1, Relaxed); self.sum.fetch_add(ns, Relaxed); self.max.fetch_max(ns, Relaxed);
        self.buckets[(64 - ns.leading_zeros() as usize).min(31)].fetch_add(1, Relaxed);
    }
    fn json(&self) -> serde_json::Value {
        serde_json::json!({"count": self.count.load(Relaxed), "sum_ns": self.sum.load(Relaxed), "max_ns": self.max.load(Relaxed),
            "log2_ns_buckets": self.buckets.iter().map(|b| b.load(Relaxed)).collect::<Vec<_>>()})
    }
}
static WAIT: [Histogram; 4] = [const { Histogram::new() }; 4];
static HOLD: [Histogram; 4] = [const { Histogram::new() }; 4];
pub struct Read<'a, T> { guard: Option<RwLockReadGuard<'a, T>>, acquired: Instant, wait: u64, label: usize }
pub struct Write<'a, T> { guard: Option<RwLockWriteGuard<'a, T>>, acquired: Instant, wait: u64, label: usize }
pub fn read<T>(lock: &RwLock<T>, label: usize) -> Read<'_, T> {
    let start = Instant::now(); let guard = lock.read().unwrap(); let acquired = Instant::now();
    Read { guard: Some(guard), acquired, wait: acquired.duration_since(start).as_nanos() as u64, label }
}
pub fn write<T>(lock: &RwLock<T>, label: usize) -> Write<'_, T> {
    let start = Instant::now(); let guard = lock.write().unwrap(); let acquired = Instant::now();
    Write { guard: Some(guard), acquired, wait: acquired.duration_since(start).as_nanos() as u64, label }
}
impl<T> Deref for Read<'_, T> { type Target = T; fn deref(&self) -> &T { self.guard.as_ref().unwrap() } }
impl<T> Deref for Write<'_, T> { type Target = T; fn deref(&self) -> &T { self.guard.as_ref().unwrap() } }
impl<T> DerefMut for Write<'_, T> { fn deref_mut(&mut self) -> &mut T { self.guard.as_mut().unwrap() } }
impl<T> Drop for Read<'_, T> { fn drop(&mut self) { let ns = self.acquired.elapsed().as_nanos() as u64; drop(self.guard.take()); WAIT[self.label].observe(self.wait); HOLD[self.label].observe(ns); } }
impl<T> Drop for Write<'_, T> { fn drop(&mut self) { let ns = self.acquired.elapsed().as_nanos() as u64; drop(self.guard.take()); WAIT[self.label].observe(self.wait); HOLD[self.label].observe(ns); } }

thread_local! { static ALLOCS: Cell<Option<u64>> = const { Cell::new(None) }; }
struct CountingAllocator;
#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator;
fn allocation() { ALLOCS.with(|c| { if let Some(n) = c.get() { c.set(Some(n + 1)); } }); }
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 { allocation(); System.alloc(layout) }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 { allocation(); System.alloc_zeroed(layout) }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 { allocation(); System.realloc(p, l, n) }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) { System.dealloc(p, l) }
}
static RELAYS: AtomicU64 = AtomicU64::new(0);
static RELAY_ALLOCS: AtomicU64 = AtomicU64::new(0);
pub struct Allocations;
impl Allocations { pub fn start() -> Self { ALLOCS.with(|c| c.set(Some(0))); Self } }
impl Drop for Allocations { fn drop(&mut self) { let n = ALLOCS.with(|c| c.replace(None).unwrap()); RELAY_ALLOCS.fetch_add(n, Relaxed); RELAYS.fetch_add(1, Relaxed); } }
pub fn report() {
    for (i, name) in ["other_read", "other_write", "relay_read", "presence_write"].iter().enumerate() {
        eprintln!("PROFILE {}", serde_json::json!({"lock": name, "wait": WAIT[i].json(), "hold": HOLD[i].json()}));
    }
    eprintln!("PROFILE {}", serde_json::json!({"relay_calls": RELAYS.load(Relaxed), "relay_alloc_or_realloc": RELAY_ALLOCS.load(Relaxed)}));
}
''')
print(f"Build with: cargo build --release --locked --manifest-path {args.destination}/Cargo.toml")
