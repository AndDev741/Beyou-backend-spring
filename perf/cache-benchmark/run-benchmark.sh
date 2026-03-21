#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Cache Benchmark Runner — Beyou
#
# Runs k6 cache benchmarks and saves results to timestamped files.
#
# Usage:
#   ./run-benchmark.sh --target domain                    # smoke test
#   ./run-benchmark.sh --target docs --label no-cache     # label the run
#   ./run-benchmark.sh --target domain --profile constant --vus 20 --duration 2m
#   ./run-benchmark.sh --target domain --compare results/domain/
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Defaults
TARGET=""
LABEL=""
PROFILE="${PROFILE:-smoke}"
VUS="${VUS:-10}"
DURATION="${DURATION:-2m}"
COMPARE_DIR=""
EXTRA_ARGS=()

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --target)    TARGET="$2"; shift 2 ;;
    --label)     LABEL="$2"; shift 2 ;;
    --profile)   PROFILE="$2"; shift 2 ;;
    --vus)       VUS="$2"; shift 2 ;;
    --duration)  DURATION="$2"; shift 2 ;;
    --compare)   COMPARE_DIR="$2"; shift 2 ;;
    *)           EXTRA_ARGS+=("$1"); shift ;;
  esac
done

# ── Compare mode ──
if [ -n "$COMPARE_DIR" ]; then
  echo "=== Comparing results in $COMPARE_DIR ==="
  echo ""
  files=($(ls -t "$COMPARE_DIR"/*.json 2>/dev/null | head -2))
  if [ ${#files[@]} -lt 2 ]; then
    echo "Need at least 2 result files in $COMPARE_DIR to compare."
    exit 1
  fi
  echo "Comparing:"
  echo "  BEFORE: ${files[1]}"
  echo "  AFTER:  ${files[0]}"
  echo ""
  python3 "$SCRIPT_DIR/compare-results.py" "${files[1]}" "${files[0]}" 2>/dev/null || \
    echo "(Install python3 for detailed comparison, or compare the JSON files manually)"
  exit 0
fi

# ── Validate target ──
if [ -z "$TARGET" ]; then
  echo "Error: --target is required (domain or docs)"
  echo "Usage: $0 --target domain|docs [--label NAME] [--profile PROFILE] [--vus N] [--duration T]"
  exit 1
fi

case "$TARGET" in
  domain) K6_SCRIPT="$SCRIPT_DIR/k6-domain-benchmark.js" ;;
  docs)   K6_SCRIPT="$SCRIPT_DIR/k6-docs-benchmark.js" ;;
  *)
    echo "Error: --target must be 'domain' or 'docs', got '$TARGET'"
    exit 1
    ;;
esac

if [ ! -f "$K6_SCRIPT" ]; then
  echo "Error: k6 script not found: $K6_SCRIPT"
  exit 1
fi

RESULTS_DIR="$SCRIPT_DIR/results/$TARGET"
mkdir -p "$RESULTS_DIR"

# Build timestamp and filename
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
if [ -n "$LABEL" ]; then
  FILENAME="bench-${TIMESTAMP}-${LABEL}"
else
  FILENAME="bench-${TIMESTAMP}"
fi

JSON_OUT="$RESULTS_DIR/${FILENAME}.json"
TEXT_OUT="$RESULTS_DIR/${FILENAME}.txt"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║              CACHE BENCHMARK                               ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  Target:    $(printf '%-46s' "$TARGET")║"
echo "║  Profile:   $(printf '%-46s' "$PROFILE")║"
echo "║  VUs:       $(printf '%-46s' "$VUS")║"
echo "║  Duration:  $(printf '%-46s' "$DURATION")║"
echo "║  Label:     $(printf '%-46s' "${LABEL:-<none>}")║"
echo "║  Output:    $(printf '%-46s' "$FILENAME")║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

k6 run \
  -e "PROFILE=$PROFILE" \
  -e "VUS=$VUS" \
  -e "DURATION=$DURATION" \
  --summary-export="$JSON_OUT" \
  "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}" \
  "$K6_SCRIPT" 2>&1 | tee "$TEXT_OUT"

echo ""
echo "Results saved to:"
echo "  JSON: $JSON_OUT"
echo "  Text: $TEXT_OUT"
echo ""
echo "To compare two runs:"
echo "  $0 --target $TARGET --compare $RESULTS_DIR"
