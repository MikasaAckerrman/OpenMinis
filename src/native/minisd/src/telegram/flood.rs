//! [M-R2] FloodWait handling and the read rate limiter — exact
//! mechanics of telegram.py:20-43 (docs/M_R2_TOOL_MAPPING.md §2.4):
//! `FLOOD_WAIT_X` waits `min(X + 1, 60)` seconds and retries up to 3
//! attempts; X above the cap returns the error to the caller IMMEDIATELY
//! (the daemon never blocks for minutes on a flood it cannot clear).
//! Reads are throttled to one per `RATE_READ_SEC` (0.3 s).
//!
//! Pure policy only: the concrete retry loop lives in `api.rs` where
//! the error type is known and the async clock is injectable for tests.

pub const READ_RATE_INTERVAL_MS: u64 = 300;
pub const MAX_ATTEMPTS: u32 = 3;
pub const WAIT_CAP_SECS: u64 = 60;

/// The pure decision of one flood encounter.
pub struct FloodBackoff;

impl FloodBackoff {
    /// Some(secs) = sleep then retry; None = give the error back to the
    /// caller without blocking the daemon.
    pub fn retry_wait_secs(flood_wait_secs: u64) -> Option<u64> {
        if flood_wait_secs > WAIT_CAP_SECS {
            return None;
        }
        Some((flood_wait_secs + 1).min(WAIT_CAP_SECS))
    }
}

/// The read throttle: at most one read call per READ_RATE_INTERVAL_MS.
pub struct RateLimiter {
    last_call_ms: Option<u64>,
}

impl RateLimiter {
    pub fn new() -> Self {
        Self { last_call_ms: None }
    }

    /// Milliseconds to sleep before this read may proceed (0 = now).
    /// The scheduled next slot advances monotonically even when the
    /// caller's clock jumps, so bursts cannot collapse into parallel
    /// reads and an NTP step backwards does not extend waits.
    pub fn wait_needed_ms(&mut self, now_ms: u64) -> u64 {
        match self.last_call_ms {
            None => {
                self.last_call_ms = Some(now_ms);
                0
            }
            Some(last) => {
                let elapsed = now_ms.saturating_sub(last);
                if elapsed >= READ_RATE_INTERVAL_MS {
                    self.last_call_ms = Some(now_ms);
                    0
                } else {
                    let remaining = READ_RATE_INTERVAL_MS - elapsed;
                    self.last_call_ms = Some(last + READ_RATE_INTERVAL_MS);
                    remaining
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flood_wait_waits_x_plus_one_up_to_the_cap() {
        assert_eq!(FloodBackoff::retry_wait_secs(3), Some(4));
        assert_eq!(FloodBackoff::retry_wait_secs(59), Some(60));
        assert_eq!(FloodBackoff::retry_wait_secs(60), Some(60));
    }

    #[test]
    fn a_flood_above_the_cap_refuses_to_block_the_daemon() {
        assert_eq!(FloodBackoff::retry_wait_secs(61), None);
        assert_eq!(FloodBackoff::retry_wait_secs(3_600), None);
    }

    #[test]
    fn the_rate_limiter_spaces_reads_by_300ms() {
        let mut rl = RateLimiter::new();
        assert_eq!(rl.wait_needed_ms(1_000), 0); // first read is free
        assert_eq!(rl.wait_needed_ms(1_050), 250); // 50ms elapsed of 300
        assert_eq!(rl.wait_needed_ms(1_600), 0); // past the window
        assert_eq!(rl.wait_needed_ms(1_600), 300); // immediately again: full wait
    }

    #[test]
    fn the_rate_limiter_never_goes_backwards() {
        let mut rl = RateLimiter::new();
        rl.wait_needed_ms(10_000);
        // A clock that did not move: still owes the full interval.
        assert_eq!(rl.wait_needed_ms(10_000), 300);
        // And a clock that jumped back (NTP) does not extend the wait.
        assert_eq!(rl.wait_needed_ms(9_000), 300);
    }
}
