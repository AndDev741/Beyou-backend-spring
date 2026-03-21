#!/usr/bin/env python3
"""
Compare two k6 --summary-export JSON files and show cache impact.
Dynamically discovers endpoint_* metrics — works for both domain and docs benchmarks.

Usage: python3 compare-results.py before.json after.json
"""

import json
import sys


def load(path):
    with open(path) as f:
        return json.load(f)


def get_metric(data, name, stat):
    """Get a metric stat from k6 summary-export JSON.

    k6 --summary-export format: {"metrics": {"name": {"avg": 123, "med": 100, ...}}}
    Uses "med" instead of "p(50)".
    """
    m = data.get("metrics", {}).get(name, {})
    val = m.get(stat)
    if val is not None:
        return val
    val = m.get("values", {}).get(stat)
    if val is not None:
        return val
    return None


def fmt_ms(val):
    if val is None:
        return "N/A"
    return f"{val:.2f} ms"


def fmt_pct(before, after):
    if before is None or after is None or before == 0:
        return "N/A"
    change = ((after - before) / before) * 100
    arrow = "\u2193" if change < 0 else "\u2191"
    return f"{arrow} {abs(change):.1f}%"


def get_p50(data, name):
    return get_metric(data, name, "med") or get_metric(data, name, "p(50)")


def get_p95(data, name):
    return get_metric(data, name, "p(95)")


def get_p99(data, name):
    return get_metric(data, name, "p(99)")


def discover_endpoint_metrics(before, after):
    """Find all endpoint_* metrics present in either file."""
    keys = set()
    for data in [before, after]:
        for key in data.get("metrics", {}).keys():
            if key.startswith("endpoint_"):
                keys.add(key)
    # Sort for stable output
    return sorted(keys)


def metric_to_label(key):
    """Convert metric key like 'endpoint_category_list' to 'Categories (list)'."""
    name = key.replace("endpoint_", "")
    parts = name.rsplit("_", 1)
    entity = parts[0].replace("_", " ").title()
    suffix = parts[1] if len(parts) > 1 else ""

    label_map = {
        "list": "(list)",
        "get": "(by ID/key)",
        "today": "(today)",
    }
    return f"{entity} {label_map.get(suffix, suffix)}".strip()


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <before.json> <after.json>")
        sys.exit(1)

    before = load(sys.argv[1])
    after = load(sys.argv[2])

    print("=" * 70)
    print("  CACHE BENCHMARK COMPARISON")
    print("=" * 70)
    print(f"  BEFORE: {sys.argv[1]}")
    print(f"  AFTER:  {sys.argv[2]}")
    print("=" * 70)

    # Overall latency
    print("\n  HTTP Request Duration (all endpoints):")
    print(f"  {'Metric':<20} {'Before':>12} {'After':>12} {'Change':>12}")
    print(f"  {'-'*56}")

    for getter, label in [
        (get_p50, "p50"),
        (get_p95, "p95"),
        (get_p99, "p99"),
        (lambda d, n: get_metric(d, n, "avg"), "avg"),
        (lambda d, n: get_metric(d, n, "max"), "max"),
        (lambda d, n: get_metric(d, n, "min"), "min"),
    ]:
        b = getter(before, "http_req_duration")
        a = getter(after, "http_req_duration")
        print(f"  {label:<20} {fmt_ms(b):>12} {fmt_ms(a):>12} {fmt_pct(b, a):>12}")

    # Per-endpoint comparison (dynamic discovery)
    endpoint_keys = discover_endpoint_metrics(before, after)
    if endpoint_keys:
        print(f"\n  Per-Endpoint p50 / p95:")
        print(
            f"  {'Endpoint':<25} {'Before p50':>10} {'After p50':>10} {'Change':>10}"
            f" {'Before p95':>12} {'After p95':>12} {'Change':>10}"
        )
        print(f"  {'-'*89}")

        for key in endpoint_keys:
            label = metric_to_label(key)
            b50 = get_p50(before, key)
            a50 = get_p50(after, key)
            b95 = get_p95(before, key)
            a95 = get_p95(after, key)
            print(
                f"  {label:<25} {fmt_ms(b50):>10} {fmt_ms(a50):>10} {fmt_pct(b50, a50):>10}"
                f" {fmt_ms(b95):>12} {fmt_ms(a95):>12} {fmt_pct(b95, a95):>10}"
            )

    # Cache-specific metrics
    hit_lat = before.get("metrics", {}).get("cache_hit_latency") or \
              after.get("metrics", {}).get("cache_hit_latency")
    if hit_lat:
        print("\n  Cache Hit Latency (iter > 0):")
        print(f"  {'Metric':<20} {'Before':>12} {'After':>12} {'Change':>12}")
        print(f"  {'-'*56}")
        for getter, label in [
            (get_p50, "p50"),
            (get_p95, "p95"),
            (lambda d, n: get_metric(d, n, "avg"), "avg"),
        ]:
            b = getter(before, "cache_hit_latency")
            a = getter(after, "cache_hit_latency")
            print(f"  {label:<20} {fmt_ms(b):>12} {fmt_ms(a):>12} {fmt_pct(b, a):>12}")

    # Throughput
    print("\n  Throughput:")
    b_rate = get_metric(before, "http_reqs", "rate")
    a_rate = get_metric(after, "http_reqs", "rate")
    b_count = get_metric(before, "http_reqs", "count")
    a_count = get_metric(after, "http_reqs", "count")
    print(f"  {'Metric':<20} {'Before':>12} {'After':>12} {'Change':>12}")
    print(f"  {'-'*56}")
    if b_rate and a_rate:
        print(f"  {'Req/s':<20} {b_rate:>11.2f} {a_rate:>11.2f}  {fmt_pct(b_rate, a_rate):>11}")
    if b_count and a_count:
        print(f"  {'Total requests':<20} {int(b_count):>12} {int(a_count):>12}")

    b_reads = get_metric(before, "total_reads", "count")
    a_reads = get_metric(after, "total_reads", "count")
    b_writes = get_metric(before, "total_writes", "count")
    a_writes = get_metric(after, "total_writes", "count")
    if b_reads and a_reads:
        print(f"  {'Reads':<20} {int(b_reads):>12} {int(a_reads):>12}")
    if b_writes and a_writes:
        print(f"  {'Writes':<20} {int(b_writes):>12} {int(a_writes):>12}")

    # Error rate
    b_err = get_metric(before, "http_req_failed", "rate")
    a_err = get_metric(after, "http_req_failed", "rate")
    if b_err is not None and a_err is not None:
        print(f"\n  Error rate: {b_err*100:.2f}% -> {a_err*100:.2f}%")

    b_rsr = get_metric(before, "read_success_rate", "rate")
    a_rsr = get_metric(after, "read_success_rate", "rate")
    if b_rsr is not None and a_rsr is not None:
        print(f"  Read success: {b_rsr*100:.1f}% -> {a_rsr*100:.1f}%")

    print("\n" + "=" * 70)


if __name__ == "__main__":
    main()
