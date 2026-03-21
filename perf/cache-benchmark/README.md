# Cache Benchmark — Beyou Backend

Measures the performance impact of caching on Beyou backend endpoints.
Two separate benchmarks: **domain** (authenticated) and **docs** (public).

## What It Tests

### Domain Benchmark (`--target domain`)

| Endpoint | Type |
|----------|------|
| `GET /category` | List |
| `GET /habit` | List |
| `GET /task` | List |
| `GET /goal` | List |
| `GET /routine` | List |
| `GET /routine/{id}` | By ID |
| `GET /routine/today` | Today's routines |
| `GET /schedule` | List |

Optionally injects writes (~5%) on categories/habits to test cache invalidation.

### Docs Benchmark (`--target docs`)

| Endpoint | Type |
|----------|------|
| `GET /docs/architecture/topics` | List |
| `GET /docs/architecture/topics/{key}` | By key |
| `GET /docs/api/controllers` | List |
| `GET /docs/api/controllers/{key}` | By key |
| `GET /docs/projects/topics` | List |
| `GET /docs/projects/topics/{key}` | By key |
| `GET /docs/blog/topics` | List |
| `GET /docs/blog/topics/{key}` | By key |
| `GET /docs/search?q={term}` | Search |

## Quick Start

```bash
# Install k6: https://grafana.com/docs/k6/latest/set-up/install-k6/

# 1. Run BEFORE enabling cache
./run-benchmark.sh --target domain --label no-cache --profile constant --vus 20 --duration 2m

# 2. Enable cache and restart server

# 3. Run AFTER enabling cache
./run-benchmark.sh --target domain --label with-cache --profile constant --vus 20 --duration 2m

# 4. Compare
./run-benchmark.sh --target domain --compare results/domain/
```

Same for docs:

```bash
./run-benchmark.sh --target docs --label no-cache --profile constant --vus 20 --duration 2m
# enable cache, restart
./run-benchmark.sh --target docs --label with-cache --profile constant --vus 20 --duration 2m
./run-benchmark.sh --target docs --compare results/docs/
```

## Profiles

| Profile | Description | Example |
|---------|-------------|---------|
| `smoke` | 1 VU, 30s (default) | `./run-benchmark.sh --target domain` |
| `constant` | Fixed VU count | `--profile constant --vus 20 --duration 2m` |
| `ramp` | Ramp up/down | `--profile ramp -e STAGES="30s:1,1m:10,1m:30,2m:50,1m:30,30s:0"` |
| `shared-iterations` | Fixed total requests | `--profile shared-iterations -e ITERATIONS=500` |

## Environment Variables

### Shared

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8099` | Server URL |
| `PROFILE` | `smoke` | Load profile |
| `VUS` | `10` | Concurrent users |
| `DURATION` | `2m` | Test duration |
| `PAUSE_MS` | `100` | Pause between iterations (ms) |
| `P95_MS` | `300` | p95 latency threshold (ms) |
| `ERR_RATE` | `0.02` | Max error rate |
| `SCENARIOS` | `all` | Comma-separated scenario filter |
| `STAGES` | `30s:1,1m:10,1m:30,2m:50,1m:30,30s:0` | Custom ramp stages |
| `ITERATIONS` | `200` | Iterations for shared-iterations profile |
| `MAX_DURATION` | `10m` | Max duration for shared-iterations profile |

### Domain Only

| Variable | Default | Description |
|----------|---------|-------------|
| `LOGIN_EMAIL` | `test@test.com` | Test user email |
| `LOGIN_PASSWORD` | `123456` | Test user password |
| `LOGIN_NAME` | `k6 Benchmark User` | Name for register |
| `WRITE_RATIO` | `0.05` | Fraction of write requests (0-1) |
| `SEED_DATA` | `false` | Create test entities in setup |

### Docs Only

| Variable | Default | Description |
|----------|---------|-------------|
| `SEARCH_TERMS` | `architecture,api,auth,database,frontend` | Search query pool |

## Key Metrics

| Metric | Description |
|--------|-------------|
| `http_req_duration p50/p95` | Overall latency |
| `cache_hit_latency` | Latency on repeat reads (should drop with cache) |
| `endpoint_*` | Per-endpoint breakdown |
| `http_reqs rate` | Requests/second |
| `total_reads` / `total_writes` | Read/write counts |
