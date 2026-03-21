/**
 * k6 Cache Benchmark — Beyou Domain Endpoints
 *
 * Measures performance of authenticated domain endpoints.
 * Run BEFORE and AFTER enabling cache to compare results.
 *
 * Auth: Registers/logs in a test user, re-logins when JWT nears expiry (15min TTL).
 *
 * Endpoints tested:
 *   - GET /category       (list)
 *   - GET /habit          (list)
 *   - GET /task           (list)
 *   - GET /goal           (list)
 *   - GET /routine        (list)
 *   - GET /routine/{id}   (by ID)
 *   - GET /routine/today  (today's routines)
 *   - GET /schedule       (list)
 *
 * Write operations (5% default): GET-then-PUT on categories/habits.
 *
 * Usage:
 *   k6 run k6-domain-benchmark.js
 *   k6 run -e PROFILE=constant -e VUS=20 -e DURATION=2m k6-domain-benchmark.js
 *   k6 run -e SEED_DATA=true k6-domain-benchmark.js
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
function envBool(name, fallback) {
  const v = (__ENV[name] || "").toLowerCase();
  if (v === "true" || v === "1" || v === "yes") return true;
  if (v === "false" || v === "0" || v === "no") return false;
  return fallback;
}
function parseStages(str) {
  return str.split(",").map((s) => {
    const [dur, target] = s.trim().split(":");
    return { duration: dur, target: Number(target) };
  });
}

// ─── Configuration ────────────────────────────────────────────────────────────

const BASE_URL = env("BASE_URL", "http://localhost:8099");
const LOGIN_EMAIL = env("LOGIN_EMAIL", "test@test.com");
const LOGIN_PASSWORD = env("LOGIN_PASSWORD", "123456");
const LOGIN_NAME = env("LOGIN_NAME", "k6 Benchmark User");
const PROFILE = env("PROFILE", "smoke");
const VUS = envNum("VUS", 10);
const DURATION = env("DURATION", "2m");
const PAUSE_MS = envNum("PAUSE_MS", 100);
const WRITE_RATIO = envNum("WRITE_RATIO", 0.05);
const P95_MS = envNum("P95_MS", 300);
const ERR_RATE = envNum("ERR_RATE", 0.02);
const SCENARIOS_FILTER = env("SCENARIOS", "all");
const SEED_DATA = envBool("SEED_DATA", false);

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
const writeSuccessRate = new Rate("write_success_rate");
const cacheHitLatency = new Trend("cache_hit_latency", true);
const cacheMissLatency = new Trend("cache_miss_latency", true);

const categoryListLatency = new Trend("endpoint_category_list", true);
const habitListLatency = new Trend("endpoint_habit_list", true);
const taskListLatency = new Trend("endpoint_task_list", true);
const goalListLatency = new Trend("endpoint_goal_list", true);
const routineListLatency = new Trend("endpoint_routine_list", true);
const routineGetLatency = new Trend("endpoint_routine_get", true);
const routineTodayLatency = new Trend("endpoint_routine_today", true);
const scheduleListLatency = new Trend("endpoint_schedule_list", true);

const totalReads = new Counter("total_reads");
const totalWrites = new Counter("total_writes");

// ─── Auth Helpers ─────────────────────────────────────────────────────────────

const JSON_HEADERS = {
  "Content-Type": "application/json",
  Accept: "application/json",
};

function doLogin() {
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email: LOGIN_EMAIL, password: LOGIN_PASSWORD }),
    { headers: JSON_HEADERS, timeout: "30s", tags: { name: "auth_login" } }
  );

  check(res, { "login status 200": (r) => r.status === 200 });

  if (res.status !== 200) {
    console.error(`Login failed: ${res.status} ${res.body}`);
    return null;
  }

  const accessToken = res.headers["Accesstoken"] || res.headers["accessToken"] || res.headers["accesstoken"];
  if (!accessToken) {
    console.error("No accessToken in login response headers");
    return null;
  }

  return accessToken;
}

// ─── Setup ────────────────────────────────────────────────────────────────────

export function setup() {
  // Try register (may fail if account exists — that's fine)
  const registerRes = http.post(
    `${BASE_URL}/auth/register`,
    JSON.stringify({
      name: LOGIN_NAME,
      email: LOGIN_EMAIL,
      password: LOGIN_PASSWORD,
      isGoogleAccount: false,
    }),
    { headers: JSON_HEADERS, timeout: "30s", tags: { name: "auth_register" } }
  );
  console.log(`Register: ${registerRes.status}`);

  // Always login to get token
  const accessToken = doLogin();
  if (!accessToken) {
    throw new Error("Setup failed: could not obtain access token");
  }

  // Seed data if requested
  if (SEED_DATA) {
    const authHeaders = {
      "Content-Type": "application/json",
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
    };

    // 3 categories
    const categoryIds = [];
    for (let i = 1; i <= 3; i++) {
      const res = http.post(
        `${BASE_URL}/category`,
        JSON.stringify({
          name: `Bench Category ${i}`,
          icon: "icon-bench",
          description: `Benchmark category ${i}`,
          level: 0,
          xp: 0,
        }),
        { headers: authHeaders, timeout: "30s", tags: { name: "seed" } }
      );
      if (res.status === 200 || res.status === 201) {
        try {
          const body = JSON.parse(res.body);
          if (body.categoryId) categoryIds.push(body.categoryId);
        } catch (_) {}
      }
    }

    // 3 habits
    for (let i = 1; i <= 3; i++) {
      http.post(
        `${BASE_URL}/habit`,
        JSON.stringify({
          name: `Bench Habit ${i}`,
          description: `Benchmark habit ${i}`,
          motivationalPhrase: "Keep going",
          iconId: "icon-bench",
          importance: 3,
          dificulty: 3,
          categoriesId: categoryIds,
          xp: 0,
          level: 0,
        }),
        { headers: authHeaders, timeout: "30s", tags: { name: "seed" } }
      );
    }

    // 3 tasks
    for (let i = 1; i <= 3; i++) {
      http.post(
        `${BASE_URL}/task`,
        JSON.stringify({
          name: `Bench Task ${i}`,
          description: `Benchmark task ${i}`,
          iconId: "icon-bench",
          importance: 3,
          difficulty: 3,
          categoriesId: categoryIds,
          oneTimeTask: false,
        }),
        { headers: authHeaders, timeout: "30s", tags: { name: "seed" } }
      );
    }

    // 2 goals
    const today = new Date().toISOString().split("T")[0];
    const futureDate = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000).toISOString().split("T")[0];
    for (let i = 1; i <= 2; i++) {
      http.post(
        `${BASE_URL}/goal`,
        JSON.stringify({
          name: `Bench Goal ${i}`,
          description: `Benchmark goal ${i}`,
          iconId: "icon-bench",
          targetValue: 100,
          unit: "points",
          currentValue: 0,
          categoriesId: categoryIds,
          motivation: "Benchmark",
          startDate: today,
          endDate: futureDate,
          status: "NOT_STARTED",
          term: "MEDIUM_TERM",
        }),
        { headers: authHeaders, timeout: "30s", tags: { name: "seed" } }
      );
    }

    // 1 routine with 2 sections (empty sections — no habit/task groups)
    http.post(
      `${BASE_URL}/routine`,
      JSON.stringify({
        name: "Bench Routine",
        iconId: "icon-bench",
        routineSections: [
          {
            name: "Morning",
            iconId: "icon-morning",
            startTime: "08:00:00",
            endTime: "10:00:00",
            taskGroup: [],
            habitGroup: [],
            favorite: false,
          },
          {
            name: "Evening",
            iconId: "icon-evening",
            startTime: "18:00:00",
            endTime: "20:00:00",
            taskGroup: [],
            habitGroup: [],
            favorite: false,
          },
        ],
      }),
      { headers: authHeaders, timeout: "30s", tags: { name: "seed" } }
    );

    console.log("Seed data created");
  }

  return {
    accessToken,
    loginEmail: LOGIN_EMAIL,
    loginPassword: LOGIN_PASSWORD,
  };
}

// ─── Per-VU State ─────────────────────────────────────────────────────────────

let currentToken = null;
let tokenObtainedAt = 0;
const TOKEN_REFRESH_MS = 14 * 60 * 1000; // Re-login at 14min (1min before 15min TTL)

function getAuthHeaders(setupData) {
  const now = Date.now();

  // Initialize token from setup data on first call
  if (!currentToken) {
    currentToken = setupData.accessToken;
    tokenObtainedAt = now;
  }

  // Re-login if token is approaching expiry
  if (now - tokenObtainedAt > TOKEN_REFRESH_MS) {
    const freshToken = doLogin();
    if (freshToken) {
      currentToken = freshToken;
      tokenObtainedAt = now;
    }
  }

  return {
    Accept: "application/json",
    Authorization: `Bearer ${currentToken}`,
  };
}

// ─── Entity Discovery ─────────────────────────────────────────────────────────

const entityIds = {
  categories: [],
  habits: [],
  tasks: [],
  goals: [],
  routines: [],
};

let discoveredDomain = false;

function discoverEntities(headers) {
  if (discoveredDomain) return;

  const endpoints = [
    { key: "categories", path: "/category" },
    { key: "habits", path: "/habit" },
    { key: "tasks", path: "/task" },
    { key: "goals", path: "/goal" },
    { key: "routines", path: "/routine" },
  ];

  for (const ep of endpoints) {
    const res = http.get(`${BASE_URL}${ep.path}`, {
      headers,
      timeout: "30s",
      tags: { name: "discovery" },
    });
    if (res.status === 200) {
      try {
        const body = JSON.parse(res.body);
        // Response is an array of entities, each with an "id" field
        if (Array.isArray(body)) {
          entityIds[ep.key] = body.map((e) => e.id).filter(Boolean);
        }
      } catch (_) {}
    }
  }

  discoveredDomain = true;
}

// ─── Request Helpers ──────────────────────────────────────────────────────────

function doGet(path, tagName, latencyTrend, headers) {
  const res = http.get(`${BASE_URL}${path}`, {
    headers,
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

// ─── Write Operations ─────────────────────────────────────────────────────────

function doWriteIfNeeded(headers) {
  if (Math.random() > WRITE_RATIO) return;
  if (!shouldRun("writes")) return;

  const writeHeaders = {
    ...headers,
    "Content-Type": "application/json",
  };

  // Randomly pick category or habit to edit
  if (Math.random() < 0.5) {
    // Edit a category: GET list -> pick one -> build EditDTO -> PUT
    const catId = pickRandom(entityIds.categories);
    if (!catId) return;

    const getRes = http.get(`${BASE_URL}/category`, {
      headers,
      timeout: "30s",
      tags: { name: "write_get_category" },
    });

    if (getRes.status === 200) {
      try {
        const cats = JSON.parse(getRes.body);
        const cat = cats.find((c) => c.id === catId);
        if (cat) {
          // CategoryEditRequestDTO: { categoryId, name, icon, description }
          const editBody = {
            categoryId: cat.id,
            name: cat.name.endsWith(" ") ? cat.name.trimEnd() : cat.name + " ",
            icon: cat.iconId,
            description: cat.description || "",
          };
          const putRes = http.put(`${BASE_URL}/category`, JSON.stringify(editBody), {
            headers: writeHeaders,
            timeout: "30s",
            tags: { name: "write_put_category" },
          });
          const ok = check(putRes, { "write status 2xx": (r) => r.status >= 200 && r.status < 300 });
          writeSuccessRate.add(ok);
          totalWrites.add(1);
        }
      } catch (_) {}
    }
  } else {
    // Edit a habit: GET list -> pick one -> build EditDTO -> PUT
    const habitId = pickRandom(entityIds.habits);
    if (!habitId) return;

    const getRes = http.get(`${BASE_URL}/habit`, {
      headers,
      timeout: "30s",
      tags: { name: "write_get_habit" },
    });

    if (getRes.status === 200) {
      try {
        const habits = JSON.parse(getRes.body);
        const habit = habits.find((h) => h.id === habitId);
        if (habit) {
          // EditHabitDTO: { habitId, name, description, motivationalPhrase, iconId, importance, dificulty, categoriesId }
          const catIds = (habit.categories || []).map((c) => c.id).filter(Boolean);
          const editBody = {
            habitId: habit.id,
            name: habit.name,
            description: habit.description
              ? habit.description.endsWith(" ")
                ? habit.description.trimEnd()
                : habit.description + " "
              : " ",
            motivationalPhrase: habit.motivationalPhrase || "",
            iconId: habit.iconId,
            importance: habit.importance,
            dificulty: habit.dificulty,
            categoriesId: catIds,
          };
          const putRes = http.put(`${BASE_URL}/habit`, JSON.stringify(editBody), {
            headers: writeHeaders,
            timeout: "30s",
            tags: { name: "write_put_habit" },
          });
          const ok = check(putRes, { "write status 2xx": (r) => r.status >= 200 && r.status < 300 });
          writeSuccessRate.add(ok);
          totalWrites.add(1);
        }
      } catch (_) {}
    }
  }
}

// ─── Main Test Function ───────────────────────────────────────────────────────

export default function (setupData) {
  const headers = getAuthHeaders(setupData);

  discoverEntities(headers);

  if (shouldRun("categories")) {
    group("categories", () => {
      doGet("/category", "GET /category", categoryListLatency, headers);
    });
  }

  if (shouldRun("habits")) {
    group("habits", () => {
      doGet("/habit", "GET /habit", habitListLatency, headers);
    });
  }

  if (shouldRun("tasks")) {
    group("tasks", () => {
      doGet("/task", "GET /task", taskListLatency, headers);
    });
  }

  if (shouldRun("goals")) {
    group("goals", () => {
      doGet("/goal", "GET /goal", goalListLatency, headers);
    });
  }

  if (shouldRun("routines")) {
    group("routines", () => {
      doGet("/routine", "GET /routine", routineListLatency, headers);

      const routineId = pickRandom(entityIds.routines);
      if (routineId) {
        doGet(`/routine/${routineId}`, "GET /routine/{id}", routineGetLatency, headers);
      }

      doGet("/routine/today", "GET /routine/today", routineTodayLatency, headers);
    });
  }

  if (shouldRun("schedules")) {
    group("schedules", () => {
      doGet("/schedule", "GET /schedule", scheduleListLatency, headers);
    });
  }

  doWriteIfNeeded(headers);

  if (PAUSE_MS > 0) {
    sleep(PAUSE_MS / 1000);
  }
}

// ─── Summary ──────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const lines = [];
  lines.push("╔══════════════════════════════════════════════════════════════╗");
  lines.push("║          DOMAIN CACHE BENCHMARK RESULTS                    ║");
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
    ["endpoint_category_list", "Categories (list)"],
    ["endpoint_habit_list", "Habits (list)"],
    ["endpoint_task_list", "Tasks (list)"],
    ["endpoint_goal_list", "Goals (list)"],
    ["endpoint_routine_list", "Routines (list)"],
    ["endpoint_routine_get", "Routines (by ID)"],
    ["endpoint_routine_today", "Routines (today)"],
    ["endpoint_schedule_list", "Schedules (list)"],
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
  const writes = metrics.total_writes;
  if (reads && reads.values) {
    lines.push(`║    Reads:           ${String(reads.values.count).padEnd(38)}║`);
  }
  if (writes && writes.values) {
    lines.push(`║    Writes:          ${String(writes.values.count).padEnd(38)}║`);
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
