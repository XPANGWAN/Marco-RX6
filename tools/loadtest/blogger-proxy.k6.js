import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const failures = new Rate("proxy_failures");
const responseTime = new Trend("proxy_response_time", true);

const target = (__ENV.TARGET_URL || "https://marcorx6app.dpdns.org/api/games?alt=json&max-results=100").trim();
const maxVus = Number(__ENV.MAX_VUS || "10");
const allowHighLoad = __ENV.ALLOW_HIGH_LOAD === "1";

if (!/^https:\/\//i.test(target)) {
  throw new Error("TARGET_URL must be an HTTPS URL.");
}
if (!Number.isInteger(maxVus) || maxVus < 1 || maxVus > 100) {
  throw new Error("MAX_VUS must be an integer from 1 to 100.");
}

const stages = maxVus <= 10
  ? [{ duration: "20s", target: maxVus }, { duration: "30s", target: maxVus }, { duration: "15s", target: 0 }]
  : [
      { duration: "20s", target: Math.min(10, maxVus) },
      { duration: "30s", target: Math.min(10, maxVus) },
      { duration: "20s", target: Math.min(25, maxVus) },
      { duration: "30s", target: Math.min(25, maxVus) },
      { duration: "20s", target: Math.min(50, maxVus) },
      { duration: "30s", target: Math.min(50, maxVus) },
      { duration: "20s", target: maxVus },
      { duration: "45s", target: maxVus },
      { duration: "20s", target: 0 }
    ];

export const options = {
  scenarios: {
    blogger_proxy: {
      executor: "ramping-vus",
      startVUs: 0,
      stages,
      gracefulRampDown: "10s"
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<8000"],
    proxy_failures: ["rate<0.05"]
  },
  discardResponseBodies: false
};

export default function () {
  const response = http.get(target, {
    headers: {
      Accept: "application/json",
      "User-Agent": "MARCO-RX6-ProxyLoadTest/1.0",
      "X-MARCO-Load-Test": "controlled"
    },
    timeout: "25s",
    tags: { endpoint: "blogger_proxy_games" }
  });

  responseTime.add(response.timings.duration);
  let validJson = false;
  let validFeed = false;
  try {
    const body = response.json();
    validJson = body !== null && typeof body === "object";
    validFeed = validJson && body.feed !== undefined &&
      (body.feed.entry === undefined || Array.isArray(body.feed.entry));
  } catch (_) {
    validJson = false;
  }

  const ok = check(response, {
    "HTTP 200": (r) => r.status === 200,
    "JSON response": () => validJson,
    "Blogger feed shape": () => validFeed
  });
  failures.add(!ok);
  sleep(1);
}
