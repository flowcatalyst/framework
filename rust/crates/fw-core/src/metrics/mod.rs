//! Metrics (background-and-metrics contract section 2): an explicit registry, never global.
//!
//! Instruments are families with fixed label names; `labels(..)` returns the series to record on
//! (cache it on hot paths). Names, labels, buckets and the cardinality bound follow the contract;
//! [`prometheus_text`] writes the exposition format with the Java writer's exact bytes.

mod prometheus;

use std::collections::BTreeMap;
use std::fmt;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, PoisonError};
use std::time::Duration;

pub use prometheus::{CONTENT_TYPE, format_number, prometheus_text};

/// The default timer buckets, in seconds.
pub const DEFAULT_BUCKETS: [f64; 11] = [
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0,
];
/// The default bound on series per family (`app.metrics.max-series`).
pub const DEFAULT_MAX_SERIES: usize = 1000;
/// The largest allowed bound on series per family.
pub const MAX_SERIES_LIMIT: usize = 100_000;

const NAME_MAX: usize = 200;
const VALUE_MAX: usize = 200;
const HELP_MAX: usize = 500;
const LABELS_MAX: usize = 10;
const BUCKETS_MAX: usize = 50;

/// A metric could not be created: a naming, label or bucket rule, or a clash with an existing
/// family of that name.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("metric {name}: {message}")]
pub struct MetricsError {
    name: String,
    message: String,
}

fn err(name: &str, message: impl Into<String>) -> MetricsError {
    MetricsError {
        name: name.to_owned(),
        message: message.into(),
    }
}

/// The type of a family.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MetricType {
    /// A counter.
    Counter,
    /// A gauge.
    Gauge,
    /// A timer, exported as a histogram.
    Histogram,
}

struct Cell {
    /// Counter and gauge value, or histogram sum, as f64 bits.
    value: AtomicU64,
    /// Histogram: per-bucket counts (not cumulative).
    buckets: Vec<AtomicU64>,
    count: AtomicU64,
}

impl Cell {
    fn new(buckets: usize) -> Self {
        Self {
            value: AtomicU64::new(0f64.to_bits()),
            buckets: (0..buckets).map(|_| AtomicU64::new(0)).collect(),
            count: AtomicU64::new(0),
        }
    }

    fn add(&self, delta: f64) {
        let mut cur = self.value.load(Ordering::Relaxed);
        loop {
            let next = (f64::from_bits(cur) + delta).to_bits();
            match self
                .value
                .compare_exchange_weak(cur, next, Ordering::Relaxed, Ordering::Relaxed)
            {
                Ok(_) => return,
                Err(actual) => cur = actual,
            }
        }
    }

    fn set(&self, v: f64) {
        self.value.store(v.to_bits(), Ordering::Relaxed);
    }

    fn get(&self) -> f64 {
        f64::from_bits(self.value.load(Ordering::Relaxed))
    }
}

struct Family {
    name: String,
    help: String,
    kind: MetricType,
    labels: Vec<String>,
    buckets: Vec<f64>,
    series: Mutex<BTreeMap<Vec<String>, Arc<Cell>>>,
    warned: AtomicBool,
}

/// A callback gauge's reader.
type Callback = Arc<dyn Fn() -> f64 + Send + Sync>;

struct Registry {
    recording: bool,
    max_series: usize,
    families: Mutex<BTreeMap<String, Arc<Family>>>,
    callbacks: Mutex<BTreeMap<String, (String, Callback)>>,
    dropped: OnceLock<Counter>,
}

/// The application's metrics registry. Cloning shares it. One per application, from
/// [`crate::Boot::metrics`]; tests make their own.
#[derive(Clone)]
pub struct Metrics(Arc<Registry>);

impl fmt::Debug for Metrics {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Metrics")
            .field("recording", &self.0.recording)
            .field("max_series", &self.0.max_series)
            .finish_non_exhaustive()
    }
}

impl Metrics {
    /// The `memory` driver: records in process; [`Metrics::snapshot`] returns every series.
    ///
    /// # Errors
    ///
    /// When `max_series` is not 1 to 100 000.
    pub fn memory(max_series: usize) -> Result<Self, MetricsError> {
        Self::with(true, max_series)
    }

    /// The `noop` driver: instruments are validated, nothing is recorded, snapshots are empty.
    #[must_use]
    pub fn noop() -> Self {
        Self(Arc::new(Registry {
            recording: false,
            max_series: DEFAULT_MAX_SERIES,
            families: Mutex::new(BTreeMap::new()),
            callbacks: Mutex::new(BTreeMap::new()),
            dropped: OnceLock::new(),
        }))
    }

    fn with(recording: bool, max_series: usize) -> Result<Self, MetricsError> {
        if !(1..=MAX_SERIES_LIMIT).contains(&max_series) {
            return Err(err(
                "app.metrics.max-series",
                format!("max series must be 1 to {MAX_SERIES_LIMIT}"),
            ));
        }
        let m = Self(Arc::new(Registry {
            recording,
            max_series,
            families: Mutex::new(BTreeMap::new()),
            callbacks: Mutex::new(BTreeMap::new()),
            dropped: OnceLock::new(),
        }));
        let dropped = m.counter(
            "fw_metrics_dropped_series_total",
            "Recordings dropped because a metric reached app.metrics.max-series.",
            &["metric"],
        )?;
        // A fresh OnceLock: the set cannot fail.
        if m.0.dropped.set(dropped).is_err() {
            return Err(err("fw_metrics_dropped_series_total", "registered twice"));
        }
        Ok(m)
    }

    /// Whether [`Metrics::snapshot`] returns what was recorded (false for `noop`).
    #[must_use]
    pub fn readable(&self) -> bool {
        self.0.recording
    }

    /// A counter family; the name ends in `_total`.
    ///
    /// # Errors
    ///
    /// On a naming or label rule, or a different family of the same name.
    pub fn counter(
        &self,
        name: &str,
        help: &str,
        labels: &[&str],
    ) -> Result<Counter, MetricsError> {
        if !name.ends_with("_total") {
            return Err(err(name, "a counter's name ends in _total"));
        }
        Ok(Counter(Instrument {
            family: self.register(name, help, MetricType::Counter, labels, &[])?,
            metrics: self.clone(),
        }))
    }

    /// A gauge family; the name ends in neither `_total` nor `_seconds`.
    ///
    /// # Errors
    ///
    /// On a naming or label rule, or a different family of the same name.
    pub fn gauge(&self, name: &str, help: &str, labels: &[&str]) -> Result<Gauge, MetricsError> {
        if name.ends_with("_total") || name.ends_with("_seconds") {
            return Err(err(
                name,
                "a gauge's name ends in neither _total nor _seconds",
            ));
        }
        if self
            .0
            .callbacks
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .contains_key(name)
        {
            return Err(err(name, "already exists as a callback gauge"));
        }
        Ok(Gauge(Instrument {
            family: self.register(name, help, MetricType::Gauge, labels, &[])?,
            metrics: self.clone(),
        }))
    }

    /// A gauge read from a callback when exported; one registration per name.
    ///
    /// # Errors
    ///
    /// On a naming rule, or a second registration of the name.
    pub fn gauge_fn(
        &self,
        name: &str,
        help: &str,
        f: impl Fn() -> f64 + Send + Sync + 'static,
    ) -> Result<(), MetricsError> {
        check_name(name)?;
        check_help(name, help)?;
        if name.ends_with("_total") || name.ends_with("_seconds") {
            return Err(err(
                name,
                "a gauge's name ends in neither _total nor _seconds",
            ));
        }
        if self
            .0
            .families
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .contains_key(name)
        {
            return Err(err(name, "already exists"));
        }
        let mut callbacks = self
            .0
            .callbacks
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        if callbacks.contains_key(name) {
            return Err(err(name, "a callback gauge can be registered once"));
        }
        callbacks.insert(name.to_owned(), (help.to_owned(), Arc::new(f)));
        Ok(())
    }

    /// A timer with the default buckets; the name ends in `_seconds`.
    ///
    /// # Errors
    ///
    /// On a naming or label rule, or a different family of the same name.
    pub fn timer(&self, name: &str, help: &str, labels: &[&str]) -> Result<Timer, MetricsError> {
        self.timer_with_buckets(name, help, &DEFAULT_BUCKETS, labels)
    }

    /// A timer with explicit bucket bounds (seconds): strictly increasing, positive, finite, at
    /// most 50.
    ///
    /// # Errors
    ///
    /// On a naming, label or bucket rule, or a different family of the same name.
    pub fn timer_with_buckets(
        &self,
        name: &str,
        help: &str,
        buckets: &[f64],
        labels: &[&str],
    ) -> Result<Timer, MetricsError> {
        if !name.ends_with("_seconds") {
            return Err(err(name, "a timer's name ends in _seconds"));
        }
        if buckets.is_empty() || buckets.len() > BUCKETS_MAX {
            return Err(err(name, format!("1 to {BUCKETS_MAX} buckets")));
        }
        let mut last = 0.0;
        for (i, b) in buckets.iter().enumerate() {
            if !b.is_finite() || *b <= 0.0 || (i > 0 && *b <= last) {
                return Err(err(
                    name,
                    "buckets must be strictly increasing, positive and finite",
                ));
            }
            last = *b;
        }
        Ok(Timer(Instrument {
            family: self.register(name, help, MetricType::Histogram, labels, buckets)?,
            metrics: self.clone(),
        }))
    }

    fn register(
        &self,
        name: &str,
        help: &str,
        kind: MetricType,
        labels: &[&str],
        buckets: &[f64],
    ) -> Result<Arc<Family>, MetricsError> {
        check_name(name)?;
        check_help(name, help)?;
        if labels.len() > LABELS_MAX {
            return Err(err(name, format!("at most {LABELS_MAX} labels")));
        }
        let mut seen: Vec<&str> = Vec::new();
        for l in labels {
            if !is_name(l)
                || l.len() > NAME_MAX
                || l.starts_with("__")
                || *l == "le"
                || *l == "quantile"
            {
                return Err(err(
                    name,
                    format!(
                        "label name must match [a-z][a-z0-9_]*, not start with __ and not be le or quantile: {l}"
                    ),
                ));
            }
            if seen.contains(l) {
                return Err(err(name, format!("label {l} twice")));
            }
            seen.push(l);
        }
        if self
            .0
            .callbacks
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .contains_key(name)
        {
            return Err(err(name, "already exists as a callback gauge"));
        }
        let mut families = self
            .0
            .families
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(existing) = families.get(name) {
            let same = existing.kind == kind
                && existing.help == help
                && existing
                    .labels
                    .iter()
                    .map(String::as_str)
                    .eq(labels.iter().copied())
                && existing.buckets == buckets;
            return if same {
                Ok(Arc::clone(existing))
            } else {
                Err(err(
                    name,
                    "already exists with another type, help, label names or buckets",
                ))
            };
        }
        let family = Arc::new(Family {
            name: name.to_owned(),
            help: help.to_owned(),
            kind,
            labels: labels.iter().map(|l| (*l).to_owned()).collect(),
            buckets: buckets.to_vec(),
            series: Mutex::new(BTreeMap::new()),
            warned: AtomicBool::new(false),
        });
        families.insert(name.to_owned(), Arc::clone(&family));
        Ok(family)
    }

    /// Every family and series recorded so far, sorted (families by name, series by label values).
    #[must_use]
    pub fn snapshot(&self) -> Snapshot {
        if !self.0.recording {
            return Snapshot {
                families: Vec::new(),
            };
        }
        let mut out: Vec<FamilySnapshot> = Vec::new();
        for f in self
            .0
            .families
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .values()
        {
            let samples = f
                .series
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .iter()
                .map(|(values, cell)| {
                    let mut cumulative = Vec::with_capacity(cell.buckets.len());
                    let mut running = 0u64;
                    for b in &cell.buckets {
                        running += b.load(Ordering::Relaxed);
                        cumulative.push(running);
                    }
                    Sample {
                        label_values: values.clone(),
                        value: cell.get(),
                        cumulative_counts: cumulative,
                        count: cell.count.load(Ordering::Relaxed),
                    }
                })
                .collect();
            out.push(FamilySnapshot {
                name: f.name.clone(),
                help: f.help.clone(),
                kind: f.kind,
                label_names: f.labels.clone(),
                buckets: f.buckets.clone(),
                samples,
            });
        }
        for (name, (help, f)) in self
            .0
            .callbacks
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .iter()
        {
            out.push(FamilySnapshot {
                name: name.clone(),
                help: help.clone(),
                kind: MetricType::Gauge,
                label_names: Vec::new(),
                buckets: Vec::new(),
                samples: vec![Sample {
                    label_values: Vec::new(),
                    value: f(),
                    cumulative_counts: Vec::new(),
                    count: 0,
                }],
            });
        }
        out.sort_by(|a, b| a.name.cmp(&b.name));
        Snapshot { families: out }
    }

    fn cell(&self, family: &Family, values: &[&str]) -> Result<Option<Arc<Cell>>, MetricsError> {
        if values.len() != family.labels.len() {
            return Err(err(
                &family.name,
                format!(
                    "expected {} label values, got {}",
                    family.labels.len(),
                    values.len()
                ),
            ));
        }
        if !self.0.recording {
            return Ok(None);
        }
        let key: Vec<String> = values.iter().map(|v| truncate(v)).collect();
        let mut series = family.series.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(c) = series.get(&key) {
            return Ok(Some(Arc::clone(c)));
        }
        if series.len() >= self.0.max_series {
            drop(series);
            if !family.warned.swap(true, Ordering::Relaxed) {
                tracing::warn!(metric = %family.name, max_series = self.0.max_series, "metric reached app.metrics.max-series; recordings of new series are dropped");
            }
            if family.name != "fw_metrics_dropped_series_total"
                && let Some(dropped) = self.0.dropped.get()
                && let Ok(s) = dropped.labels(&[&family.name])
            {
                s.inc();
            }
            return Ok(None);
        }
        let cell = Arc::new(Cell::new(family.buckets.len()));
        series.insert(key, Arc::clone(&cell));
        Ok(Some(cell))
    }
}

fn truncate(v: &str) -> String {
    v.chars().take(VALUE_MAX).collect()
}

fn is_name(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty()
        && b[0].is_ascii_lowercase()
        && b.iter()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'_')
}

fn check_name(name: &str) -> Result<(), MetricsError> {
    if !is_name(name) || name.len() > NAME_MAX {
        return Err(err(
            name,
            "name must match [a-z][a-z0-9_]* and be at most 200 characters",
        ));
    }
    Ok(())
}

fn check_help(name: &str, help: &str) -> Result<(), MetricsError> {
    if help.trim().is_empty() || help.chars().count() > HELP_MAX {
        return Err(err(
            name,
            "help must not be blank and at most 500 characters",
        ));
    }
    Ok(())
}

struct Instrument {
    family: Arc<Family>,
    metrics: Metrics,
}

impl Clone for Instrument {
    fn clone(&self) -> Self {
        Self {
            family: Arc::clone(&self.family),
            metrics: self.metrics.clone(),
        }
    }
}

impl fmt::Debug for Instrument {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.family.name)
    }
}

/// A counter family.
#[derive(Clone, Debug)]
pub struct Counter(Instrument);

/// One counter series.
#[derive(Clone)]
pub struct CounterSeries(Option<Arc<Cell>>);

impl fmt::Debug for CounterSeries {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("CounterSeries")
    }
}

impl Counter {
    /// The series for these label values (values over 200 characters are cut).
    ///
    /// # Errors
    ///
    /// When the number of values differs from the family's label names (a programming error).
    pub fn labels(&self, values: &[&str]) -> Result<CounterSeries, MetricsError> {
        self.0
            .metrics
            .cell(&self.0.family, values)
            .map(CounterSeries)
    }

    /// Adds one, on a family without labels.
    pub fn inc(&self) {
        if let Ok(s) = self.labels(&[]) {
            s.inc();
        }
    }
}

impl CounterSeries {
    /// Adds one.
    pub fn inc(&self) {
        self.inc_by(1.0);
    }

    /// Adds an amount; a negative or non-finite amount is ignored.
    pub fn inc_by(&self, amount: f64) {
        if let Some(c) = &self.0
            && amount >= 0.0
            && amount.is_finite()
        {
            c.add(amount);
        }
    }
}

/// A gauge family.
#[derive(Clone, Debug)]
pub struct Gauge(Instrument);

/// One gauge series.
#[derive(Clone)]
pub struct GaugeSeries(Option<Arc<Cell>>);

impl fmt::Debug for GaugeSeries {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("GaugeSeries")
    }
}

impl Gauge {
    /// The series for these label values.
    ///
    /// # Errors
    ///
    /// When the number of values differs from the family's label names.
    pub fn labels(&self, values: &[&str]) -> Result<GaugeSeries, MetricsError> {
        self.0.metrics.cell(&self.0.family, values).map(GaugeSeries)
    }
}

impl GaugeSeries {
    /// Sets the value.
    pub fn set(&self, v: f64) {
        if let Some(c) = &self.0 {
            c.set(v);
        }
    }

    /// Adds to the value.
    pub fn inc(&self, d: f64) {
        if let Some(c) = &self.0 {
            c.add(d);
        }
    }

    /// Subtracts from the value.
    pub fn dec(&self, d: f64) {
        self.inc(-d);
    }
}

/// A timer family (a histogram of seconds).
#[derive(Clone, Debug)]
pub struct Timer(Instrument);

/// One timer series.
#[derive(Clone)]
pub struct TimerSeries {
    cell: Option<Arc<Cell>>,
    bounds: Arc<Family>,
}

impl fmt::Debug for TimerSeries {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("TimerSeries")
    }
}

impl Timer {
    /// The series for these label values.
    ///
    /// # Errors
    ///
    /// When the number of values differs from the family's label names.
    pub fn labels(&self, values: &[&str]) -> Result<TimerSeries, MetricsError> {
        let cell = self.0.metrics.cell(&self.0.family, values)?;
        Ok(TimerSeries {
            cell,
            bounds: Arc::clone(&self.0.family),
        })
    }
}

impl TimerSeries {
    /// Records a duration.
    pub fn record(&self, d: Duration) {
        let Some(c) = &self.cell else { return };
        let secs = d.as_secs_f64();
        if let Some(i) = self.bounds.buckets.iter().position(|b| secs <= *b)
            && let Some(bucket) = c.buckets.get(i)
        {
            bucket.fetch_add(1, Ordering::Relaxed);
        }
        c.count.fetch_add(1, Ordering::Relaxed);
        c.add(secs);
    }
}

/// Every family recorded, sorted.
#[derive(Debug, Clone, PartialEq)]
pub struct Snapshot {
    families: Vec<FamilySnapshot>,
}

impl Snapshot {
    /// The families, sorted by name.
    #[must_use]
    pub fn families(&self) -> &[FamilySnapshot] {
        &self.families
    }

    /// A snapshot built by hand (tests of exporters).
    #[must_use]
    pub fn from_families(families: Vec<FamilySnapshot>) -> Self {
        Self { families }
    }
}

/// One family in a snapshot.
#[derive(Debug, Clone, PartialEq)]
pub struct FamilySnapshot {
    /// The name.
    pub name: String,
    /// The help text.
    pub help: String,
    /// The type.
    pub kind: MetricType,
    /// The label names.
    pub label_names: Vec<String>,
    /// Histogram bucket bounds (empty otherwise).
    pub buckets: Vec<f64>,
    /// The series, sorted by label values.
    pub samples: Vec<Sample>,
}

/// One series in a snapshot.
#[derive(Debug, Clone, PartialEq)]
pub struct Sample {
    /// The label values, in label-name order.
    pub label_values: Vec<String>,
    /// The counter or gauge value, or the histogram's sum.
    pub value: f64,
    /// Histogram: cumulative count per bucket bound.
    pub cumulative_counts: Vec<u64>,
    /// Histogram: total count.
    pub count: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn naming_rules() {
        let m = Metrics::memory(10).unwrap();
        assert!(m.counter("jobs", "h", &[]).is_err());
        assert!(m.counter("Jobs_total", "h", &[]).is_err());
        assert!(m.counter("jobs_total", " ", &[]).is_err());
        assert!(m.counter("jobs_total", "h", &["le"]).is_err());
        assert!(m.counter("jobs_total", "h", &["__x"]).is_err());
        assert!(m.counter("jobs_total", "h", &["a", "a"]).is_err());
        assert!(m.gauge("queue_seconds", "h", &[]).is_err());
        assert!(m.timer("t", "h", &[]).is_err());
        assert!(
            m.timer_with_buckets("t_seconds", "h", &[1.0, 0.5], &[])
                .is_err()
        );
        let a = m.counter("jobs_total", "h", &["kind"]).unwrap();
        assert!(m.counter("jobs_total", "h", &["kind"]).is_ok());
        assert!(m.counter("jobs_total", "other", &["kind"]).is_err());
        assert!(a.labels(&[]).is_err());
    }

    #[test]
    fn cardinality_is_bounded_and_counted() {
        let m = Metrics::memory(2).unwrap();
        let c = m.counter("hits_total", "h", &["k"]).unwrap();
        for k in ["a", "b", "c", "d"] {
            c.labels(&[k]).unwrap().inc();
        }
        let s = m.snapshot();
        let hits = s
            .families()
            .iter()
            .find(|f| f.name == "hits_total")
            .unwrap();
        assert_eq!(hits.samples.len(), 2);
        let dropped = s
            .families()
            .iter()
            .find(|f| f.name == "fw_metrics_dropped_series_total")
            .unwrap();
        assert_eq!(
            dropped.samples[0].label_values,
            vec!["hits_total".to_owned()]
        );
        assert!((dropped.samples[0].value - 2.0).abs() < f64::EPSILON);
    }

    #[test]
    fn noop_validates_and_records_nothing() {
        let m = Metrics::noop();
        assert!(m.counter("bad", "h", &[]).is_err());
        m.counter("ok_total", "h", &[]).unwrap().inc();
        assert!(m.snapshot().families().is_empty());
        assert!(!m.readable());
    }

    #[test]
    fn label_values_are_cut_to_200_characters() {
        let m = Metrics::memory(10).unwrap();
        let c = m.counter("x_total", "h", &["v"]).unwrap();
        c.labels(&[&"a".repeat(300)]).unwrap().inc();
        let s = m.snapshot();
        let x = s.families().iter().find(|f| f.name == "x_total").unwrap();
        assert_eq!(x.samples[0].label_values[0].len(), 200);
    }

    #[test]
    fn histograms_are_cumulative() {
        let m = Metrics::memory(10).unwrap();
        let t = m
            .timer_with_buckets("op_seconds", "h", &[0.1, 1.0], &[])
            .unwrap();
        let s = t.labels(&[]).unwrap();
        s.record(Duration::from_millis(50));
        s.record(Duration::from_millis(500));
        s.record(Duration::from_secs(5));
        let snap = m.snapshot();
        let f = snap
            .families()
            .iter()
            .find(|f| f.name == "op_seconds")
            .unwrap();
        assert_eq!(f.samples[0].cumulative_counts, vec![1, 2]);
        assert_eq!(f.samples[0].count, 3);
    }
}
