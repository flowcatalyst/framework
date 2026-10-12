//! The framework's core (Rust design, capabilities section 1).
//!
//! R0 holds only the TSID codec; the application lifecycle, config, `Context` and the rest arrive
//! with milestone R1.

pub mod tsid;
