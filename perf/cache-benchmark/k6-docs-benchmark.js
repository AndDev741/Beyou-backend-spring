/**
 * k6 Cache Benchmark — Beyou Docs Endpoints
 *
 * Measures performance of public docs endpoints (no auth required).
 * Run BEFORE and AFTER enabling cache to compare results.
 *
 * Endpoints tested:
 *   - GET /docs/architecture/topics        (list)
 *   - GET /docs/architecture/topics/{key}  (by key)
 *   - GET /docs/api/controllers            (list)
 *   - GET /docs/api/controllers/{key}      (by key)
 *   - GET /docs/projects/topics            (list)
 *   - GET /docs/projects/topics/{key}      (by key)
 *   - GET /docs/blog/topics                (list)
 *   - GET /docs/blog/topics/{key}          (by key)
 *   - GET /docs/search?q={term}            (search)
 *
 * Usage:
 *   k6 run k6-docs-benchmark.js
 *   k6 run -e PROFILE=constant -e VUS=20 -e DURATION=2m k6-docs-benchmark.js
 */

import http from "k6/http";
import { check, sleep, group } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

// ─── Helpers ──────────────────────────────────────────────────────────────────

function env(name, fallback) {
  return __ENV[name] || fallback;
}
function envNum(name, fallback) {
  const v = __ENV[name];
  return v ? Number(v) : fallback;
}
function parseStages(str) {
  return str.split(",").map((s) => {
    const [dur, target] = s.trim().split(":");
    return { duration: dur, target: Number(target) };
  });
}

// ─── Configuration ────────────────────────────────────────────────────────────

const BASE_URL = env("BASE_URL", "http://localhost:8099");
const PROFILE = env("PROFILE", "smoke");
const VUS = envNum("VUS", 10);
const DURATION = env("DURATION", "2m");
const PAUSE_MS = envNum("PAUSE_MS", 100);
const P95_MS = envNum("P95_MS", 300);
const ERR_RATE = envNum("ERR_RATE", 0.02);
const SCENARIOS_FILTER = env("SCENARIOS", "all");
const SEARCH_TERMS = env("SEARCH_TERMS", "architecture,api,auth,database,frontend").split(",");

// ─── Profiles ─────────────────────────────────────────────────────────────────

function buildOptions() {
  const thresholds = {
    http_req_failed: [{ threshold: `rate<${ERR_RATE}`, abortOnFail: false }],
    http_req_duration: [`p(95)<${P95_MS}`],
    cache_hit_latency: ["p(50)<200", `p(95)<${P95_MS}`],
    cache_miss_latency: [`p(95)<${P95_MS * 2}`],
    read_success_rate: ["rate>0.98"],
  };

  switch (PROFILE) {
    case "constant":
      return {
        scenarios: {
          cache_bench: {
            executor: "constant-vus",
            vus: VUS,
            duration: DURATION,
          },
        },
        thresholds,
      };
    case "ramp": {
      const defaultStages = "30s:1,1m:10,1m:30,2m:50,1m:30,30s:0";
      const stages = parseStages(env("STAGES", defaultStages));
      return {
        scenarios: {
          cache_bench: {
            executor: "ramping-vus",
            startVUs: 1,
            stages,
            gracefulRampDown: "30s",
          },
        },
        thresholds,
      };
    }
    case "shared-iterations":
      return {
        scenarios: {
          cache_bench: {
            executor: "shared-iterations",
            vus: VUS,
            iterations: envNum("ITERATIONS", 200),
            maxDuration: env("MAX_DURATION", "10m"),
          },
        },
        thresholds,
      };
    case "smoke":
    default:
      return {
        scenarios: {
          cache_bench: {
            executor: "constant-vus",
            vus: 1,
            duration: "30s",
          },
        },
        thresholds,
      };
  }
}

export const options = buildOptions();

// ─── Custom Metrics ───────────────────────────────────────────────────────────

const readSuccessRate = new Rate("read_success_rate");
const cacheHitLatency = new Trend("cache_hit_latency", true);
const cacheMissLatency = new Trend("cache_miss_latency", true);

const archListLatency = new Trend("endpoint_arch_list", true);
const archGetLatency = new Trend("endpoint_arch_get", true);
const apiListLatency = new Trend("endpoint_api_list", true);
const apiGetLatency = new Trend("endpoint_api_get", true);
const projectsListLatency = new Trend("endpoint_projects_list", true);
const projectsGetLatency = new Trend("endpoint_projects_get", true);
const blogListLatency = new Trend("endpoint_blog_list", true);
const blogGetLatency = new Trend("endpoint_blog_get", true);
const searchLatency = new Trend("endpoint_search", true);

const totalReads = new Counter("total_reads");

// ─── Headers ──────────────────────────────────────────────────────────────────

const READ_HEADERS = { Accept: "application/json" };

// ─── Entity Discovery ─────────────────────────────────────────────────────────

const topicKeys = {
  architecture: [],
  api: [],
  projects: [],
  blog: [],
};

let discovered = false;

function discoverTopics() {
  if (discovered) return;

  const endpoints = [
    { key: "architecture", path: "/docs/architecture/topics" },
    { key: "api", path: "/docs/api/controllers" },
    { key: "projects", path: "/docs/projects/topics" },
    { key: "blog", path: "/docs/blog/topics" },
  ];

  for (const ep of endpoints) {
    const res = http.get(`${BASE_URL}${ep.path}`, {
      headers: READ_HEADERS,
      timeout: "30s",
      tags: { name: "discovery" },
    });
    if (res.status === 200) {
      try {
        const body = JSON.parse(res.body);
        // Response is a List (array), each item has a "key" field
        if (Array.isArray(body)) {
          topicKeys[ep.key] = body.map((item) => item.key).filter(Boolean);
        }
      } catch (_) {}
    }
  }

  discovered = true;
}

// ─── Request Helpers ──────────────────────────────────────────────────────────

function doGet(path, tagName, latencyTrend) {
  const res = http.get(`${BASE_URL}${path}`, {
    headers: READ_HEADERS,
    timeout: "30s",
    tags: { name: tagName },
  });

  const ok = check(res, {
    "status is 200": (r) => r.status === 200,
  });

  readSuccessRate.add(ok);
  totalReads.add(1);

  if (latencyTrend) latencyTrend.add(res.timings.duration);

  if (__ITER > 0) {
    cacheHitLatency.add(res.timings.duration);
  } else {
    cacheMissLatency.add(res.timings.duration);
  }

  return res;
}

function pickRandom(arr) {
  if (!arr || arr.length === 0) return null;
  return arr[Math.floor(Math.random() * arr.length)];
}

function shouldRun(scenario) {
  if (SCENARIOS_FILTER === "all") return true;
  return SCENARIOS_FILTER.split(",")
    .map((s) => s.trim())
    .includes(scenario);
}

// ─── Main Test Function ───────────────────────────────────────────────────────

export default function () {
  discoverTopics();

  // ── Architecture docs ──
  if (shouldRun("architecture")) {
    group("architecture", () => {
      doGet("/docs/architecture/topics", "GET /docs/architecture/topics", archListLatency);

      const key = pickRandom(topicKeys.architecture);
      if (key) {
        doGet(`/docs/architecture/topics/${key}`, "GET /docs/architecture/topics/{key}", archGetLatency);
      }
    });
  }

  // ── API docs ──
  if (shouldRun("api")) {
    group("api", () => {
      doGet("/docs/api/controllers", "GET /docs/api/controllers", apiListLatency);

      const key = pickRandom(topicKeys.api);
      if (key) {
        doGet(`/docs/api/controllers/${key}`, "GET /docs/api/controllers/{key}", apiGetLatency);
      }
    });
  }

  // ── Project docs ──
  if (shouldRun("projects")) {
    group("projects", () => {
      doGet("/docs/projects/topics", "GET /docs/projects/topics", projectsListLatency);

      const key = pickRandom(topicKeys.projects);
      if (key) {
        doGet(`/docs/projects/topics/${key}`, "GET /docs/projects/topics/{key}", projectsGetLatency);
      }
    });
  }

  // ── Blog docs ──
  if (shouldRun("blog")) {
    group("blog", () => {
      doGet("/docs/blog/topics", "GET /docs/blog/topics", blogListLatency);

      const key = pickRandom(topicKeys.blog);
      if (key) {
        doGet(`/docs/blog/topics/${key}`, "GET /docs/blog/topics/{key}", blogGetLatency);
      }
    });
  }

  // ── Search ──
  if (shouldRun("search")) {
    group("search", () => {
      const term = pickRandom(SEARCH_TERMS);
      doGet(`/docs/search?q=${encodeURIComponent(term)}`, "GET /docs/search", searchLatency);
    });
  }

  if (PAUSE_MS > 0) {
    sleep(PAUSE_MS / 1000);
  }
}

// ─── Summary ──────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const lines = [];
  lines.push("╔══════════════════════════════════════════════════════════════╗");
  lines.push("║           DOCS CACHE BENCHMARK RESULTS                     ║");
  lines.push("╠══════════════════════════════════════════════════════════════╣");

  const metrics = data.metrics || {};

  const dur = metrics.http_req_duration;
  if (dur && dur.values) {
    lines.push(`║  HTTP Request Duration (all endpoints):                     ║`);
    lines.push(`║    p50:  ${String(dur.values["p(50)"].toFixed(2) + " ms").padEnd(48)}║`);
    lines.push(`║    p95:  ${String(dur.values["p(95)"].toFixed(2) + " ms").padEnd(48)}║`);
    lines.push(`║    p99:  ${String(dur.values["p(99)"].toFixed(2) + " ms").padEnd(48)}║`);
    lines.push(`║    avg:  ${String(dur.values["avg"].toFixed(2) + " ms").padEnd(48)}║`);
    lines.push(`║    max:  ${String(dur.values["max"].toFixed(2) + " ms").padEnd(48)}║`);
  }

  const hitLat = metrics.cache_hit_latency;
  if (hitLat && hitLat.values) {
    lines.push(`║                                                            ║`);
    lines.push(`║  Cache-Hit Latency (iter > 0, likely cached):              ║`);
    lines.push(`║    p50:  ${String(hitLat.values["p(50)"].toFixed(2) + " ms").padEnd(48)}║`);
    lines.push(`║    p95:  ${String(hitLat.values["p(95)"].toFixed(2) + " ms").padEnd(48)}║`);
    lines.push(`║    avg:  ${String(hitLat.values["avg"].toFixed(2) + " ms").padEnd(48)}║`);
  }

  const endpoints = [
    ["endpoint_arch_list", "Architecture (list)"],
    ["endpoint_arch_get", "Architecture (by key)"],
    ["endpoint_api_list", "API Controllers (list)"],
    ["endpoint_api_get", "API Controllers (by key)"],
    ["endpoint_projects_list", "Projects (list)"],
    ["endpoint_projects_get", "Projects (by key)"],
    ["endpoint_blog_list", "Blog (list)"],
    ["endpoint_blog_get", "Blog (by key)"],
    ["endpoint_search", "Search"],
  ];

  lines.push(`║                                                            ║`);
  lines.push(`║  Per-Endpoint Latency (p50 / p95):                         ║`);
  for (const [key, label] of endpoints) {
    const m = metrics[key];
    if (m && m.values) {
      const p50 = m.values["p(50)"].toFixed(1);
      const p95 = m.values["p(95)"].toFixed(1);
      const padded = `${label}: ${p50}ms / ${p95}ms`;
      lines.push(`║    ${padded.padEnd(54)}║`);
    }
  }

  const reqs = metrics.http_reqs;
  if (reqs && reqs.values) {
    lines.push(`║                                                            ║`);
    lines.push(`║  Throughput:                                               ║`);
    lines.push(`║    Total requests:  ${String(reqs.values.count).padEnd(38)}║`);
    lines.push(`║    Req/s:           ${String(reqs.values.rate.toFixed(2)).padEnd(38)}║`);
  }

  const reads = metrics.total_reads;
  if (reads && reads.values) {
    lines.push(`║    Reads:           ${String(reads.values.count).padEnd(38)}║`);
  }

  const failed = metrics.http_req_failed;
  if (failed && failed.values) {
    lines.push(`║    Error rate:      ${String((failed.values.rate * 100).toFixed(2) + "%").padEnd(38)}║`);
  }

  lines.push("╚══════════════════════════════════════════════════════════════╝");

  console.log(lines.join("\n"));

  return {
    stdout: lines.join("\n") + "\n",
  };
}
