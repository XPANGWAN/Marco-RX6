# MARCO RX6 Blogger Proxy load test

This is a controlled k6 test for the public games feed used by the Android app:

`https://marcorx6app.dpdns.org/api/games?alt=json&max-results=100`

The test sends read-only GET requests and checks HTTP status, JSON validity, Blogger feed shape, latency, and request failure rate. Repeating the same feed URL intentionally exercises the proxy's cache path as well as its upstream/cache-miss behavior.

## Safety

- Start with 10 virtual users. Increase to 25, 50, then 100 only after reviewing each result.
- Do not run multiple load tests at the same time.
- Stop the run if the endpoint starts returning sustained 429/5xx responses or latency rises sharply.
- More than 100 virtual users is blocked unless `ALLOW_HIGH_LOAD=1` is explicitly set.
- Do not use this script against any endpoint you do not own or have permission to test.
- k6 virtual users are simulated clients, not proof of an equal number of long-lived real users.
- A successful feed test does not test Blogger publishing/authenticated write operations.

## Run locally

Install k6, then run from the repository root:

```sh
k6 run -e MAX_VUS=10 tools/loadtest/blogger-proxy.k6.js
k6 run -e MAX_VUS=25 tools/loadtest/blogger-proxy.k6.js
k6 run -e MAX_VUS=50 tools/loadtest/blogger-proxy.k6.js
k6 run -e MAX_VUS=100 tools/loadtest/blogger-proxy.k6.js
```

For a different authorized staging endpoint:

```sh
k6 run -e TARGET_URL=https://your-staging-host.example/api/games -e MAX_VUS=10 tools/loadtest/blogger-proxy.k6.js
```

A 500-VU run requires an explicit opt-in:

```sh
ALLOW_HIGH_LOAD=1 k6 run -e MAX_VUS=500 tools/loadtest/blogger-proxy.k6.js
```

## GitHub Actions

Run the manual workflow **Blogger Proxy Load Test** from the Actions tab. It defaults to 10 VUs; choose a higher level only after reviewing the previous run. The workflow is manual-only and does not run during normal APK builds.

## Interpreting results

Review:
- `http_req_failed`: total HTTP failure rate.
- `http_req_duration`: p50/p90/p95 latency.
- `checks`: HTTP 200, valid JSON, and expected Blogger feed structure.
- `iterations`: completed feed requests.

A passing threshold is a useful baseline, not a capacity guarantee. Cloudflare plan limits, upstream Blogger throttling, cache configuration, and origin resources still determine real production capacity. Record the date, VU level, duration, and p95/error rate for each run.
