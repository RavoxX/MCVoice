//! RFC 6479 style anti-replay window (spec section 7.5).

pub const WINDOW_SIZE: u64 = 1024;
const WORDS: usize = (WINDOW_SIZE / 64) as usize;

#[derive(Debug, Clone, Default)]
pub struct ReplayWindow {
    top: u64,
    bitmap: [u64; WORDS],
}

impl ReplayWindow {
    fn bit(c: u64) -> (usize, u64) {
        let idx = c % WINDOW_SIZE;
        ((idx / 64) as usize, 1u64 << (idx % 64))
    }

    /// Whether counter `c` would be accepted. Does not modify the window.
    pub fn check(&self, c: u64) -> bool {
        if c == 0 {
            return false;
        }
        if c > self.top {
            return true;
        }
        if self.top - c >= WINDOW_SIZE {
            return false;
        }
        let (w, m) = Self::bit(c);
        self.bitmap[w] & m == 0
    }

    /// Mark `c` as seen (only after `check` and successful authentication).
    /// Returns true if `c` advanced the window.
    pub fn update(&mut self, c: u64) -> bool {
        let mut new_top = false;
        if c > self.top {
            if c - self.top >= WINDOW_SIZE {
                self.bitmap = [0; WORDS];
            } else {
                for i in self.top + 1..=c {
                    let (w, m) = Self::bit(i);
                    self.bitmap[w] &= !m;
                }
            }
            self.top = c;
            new_top = true;
        }
        let (w, m) = Self::bit(c);
        self.bitmap[w] |= m;
        new_top
    }

    pub fn check_and_update(&mut self, c: u64) -> bool {
        if !self.check(c) {
            return false;
        }
        self.update(c);
        true
    }
}
