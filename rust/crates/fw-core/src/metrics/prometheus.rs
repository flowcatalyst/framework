//! The Prometheus text exposition format 0.0.4, byte for byte as the Java `PrometheusText`
//! writes it (background-and-metrics contract section 3).

use super::{MetricType, Snapshot};

/// The content type of the exposition.
pub const CONTENT_TYPE: &str = "text/plain; version=0.0.4; charset=utf-8";

/// Renders a snapshot: per family `# HELP` and `# TYPE`, then one line per series; timers as
/// histograms (`_bucket` with cumulative counts and `le`, `+Inf`, then `_sum` and `_count`).
#[must_use]
pub fn prometheus_text(snapshot: &Snapshot) -> String {
    let mut out = String::new();
    for f in snapshot.families() {
        let kind = match f.kind {
            MetricType::Counter => "counter",
            MetricType::Gauge => "gauge",
            MetricType::Histogram => "histogram",
        };
        out.push_str("# HELP ");
        out.push_str(&f.name);
        out.push(' ');
        out.push_str(&escape_help(&f.help));
        out.push('\n');
        out.push_str("# TYPE ");
        out.push_str(&f.name);
        out.push(' ');
        out.push_str(kind);
        out.push('\n');
        for s in &f.samples {
            match f.kind {
                MetricType::Histogram => {
                    let bucket = format!("{}_bucket", f.name);
                    for (bound, count) in f.buckets.iter().zip(&s.cumulative_counts) {
                        #[expect(
                            clippy::cast_precision_loss,
                            reason = "counts are exported as doubles, as in Java"
                        )]
                        line(
                            &mut out,
                            &bucket,
                            &f.label_names,
                            &s.label_values,
                            Some(&format_number(*bound)),
                            *count as f64,
                        );
                    }
                    #[expect(
                        clippy::cast_precision_loss,
                        reason = "counts are exported as doubles, as in Java"
                    )]
                    let count = s.count as f64;
                    line(
                        &mut out,
                        &bucket,
                        &f.label_names,
                        &s.label_values,
                        Some("+Inf"),
                        count,
                    );
                    line(
                        &mut out,
                        &format!("{}_sum", f.name),
                        &f.label_names,
                        &s.label_values,
                        None,
                        s.value,
                    );
                    line(
                        &mut out,
                        &format!("{}_count", f.name),
                        &f.label_names,
                        &s.label_values,
                        None,
                        count,
                    );
                }
                MetricType::Counter | MetricType::Gauge => {
                    line(
                        &mut out,
                        &f.name,
                        &f.label_names,
                        &s.label_values,
                        None,
                        s.value,
                    );
                }
            }
        }
    }
    out
}

fn line(
    out: &mut String,
    name: &str,
    names: &[String],
    values: &[String],
    le: Option<&str>,
    value: f64,
) {
    out.push_str(name);
    if !names.is_empty() || le.is_some() {
        out.push('{');
        for (i, (n, v)) in names.iter().zip(values).enumerate() {
            if i > 0 {
                out.push(',');
            }
            // Writing to a String cannot fail.
            out.push_str(&format!("{n}=\"{}\"", escape_value(v)));
        }
        if let Some(le) = le {
            if !names.is_empty() {
                out.push(',');
            }
            out.push_str(&format!("le=\"{le}\""));
        }
        out.push('}');
    }
    out.push(' ');
    out.push_str(&format_number(value));
    out.push('\n');
}

fn escape_help(help: &str) -> String {
    help.replace('\\', "\\\\").replace('\n', "\\n")
}

fn escape_value(v: &str) -> String {
    v.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
}

/// A number as the Java writer prints it: `NaN`, `+Inf`, `-Inf`; an integral value below 10^15 in
/// magnitude without a fraction; anything else as Java's `Double.toString` (shortest round-trip
/// digits, plain between 10^-3 and 10^7, otherwise `d.dddE±n`).
#[must_use]
pub fn format_number(v: f64) -> String {
    if v.is_nan() {
        return "NaN".to_owned();
    }
    if v.is_infinite() {
        return if v > 0.0 { "+Inf" } else { "-Inf" }.to_owned();
    }
    if v == v.round() && v.abs() < 1e15 {
        #[expect(
            clippy::cast_possible_truncation,
            reason = "integral and below 10^15 in magnitude"
        )]
        return (v as i64).to_string();
    }
    java_double(v)
}

/// Java's `Double.toString` for a finite value.
fn java_double(v: f64) -> String {
    if v == 0.0 {
        return if v.is_sign_negative() { "-0.0" } else { "0.0" }.to_owned();
    }
    // Rust's `{:e}` gives the shortest round-trip digits: "d.ddde-7".
    let sci = format!("{:e}", v.abs());
    let (mantissa, exp) = sci.split_once('e').unwrap_or((&sci, "0"));
    let exp: i32 = exp.parse().unwrap_or(0);
    let digits: String = mantissa.chars().filter(char::is_ascii_digit).collect();
    let sign = if v < 0.0 { "-" } else { "" };
    let abs = v.abs();
    if (1e-3..1e7).contains(&abs) {
        // Plain notation: the decimal point after digit (exp + 1).
        let point = exp + 1;
        let body = if point <= 0 {
            format!(
                "0.{}{}",
                "0".repeat(usize::try_from(-point).unwrap_or(0)),
                digits
            )
        } else {
            let point = usize::try_from(point).unwrap_or(0);
            if digits.len() > point {
                format!("{}.{}", &digits[..point], &digits[point..])
            } else {
                format!("{}{}.0", digits, "0".repeat(point - digits.len()))
            }
        };
        format!("{sign}{body}")
    } else {
        let (first, rest) = digits.split_at(1);
        let rest = if rest.is_empty() { "0" } else { rest };
        format!("{sign}{first}.{rest}E{exp}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_as_java_prints_them() {
        assert_eq!(format_number(1.0), "1");
        assert_eq!(format_number(-3.0), "-3");
        assert_eq!(format_number(0.005), "0.005");
        assert_eq!(format_number(2.5), "2.5");
        assert_eq!(format_number(0.001), "0.001");
        assert_eq!(format_number(0.0001), "1.0E-4");
        assert_eq!(format_number(0.000_123_4), "1.234E-4");
        assert_eq!(format_number(1e15), "1.0E15");
        assert_eq!(format_number(1.5e7), "15000000");
        assert_eq!(format_number(1.5e15), "1.5E15");
        assert_eq!(format_number(12_500_000.5), "1.25000005E7");
        assert_eq!(format_number(1_234_567.5), "1234567.5");
        assert_eq!(format_number(f64::NAN), "NaN");
        assert_eq!(format_number(f64::INFINITY), "+Inf");
        assert_eq!(format_number(f64::NEG_INFINITY), "-Inf");
        assert_eq!(format_number(0.1 + 0.2), "0.30000000000000004");
    }
}
