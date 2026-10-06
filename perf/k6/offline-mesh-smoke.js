// CI load gate for the offline settlement path.
// Every request runs the full parse + 4× ECDSA verification; the first is
// SETTLED and the rest are idempotent DUPLICATEs (verification still runs).
//
//   BUNDLE_B64=... k6 run perf/k6/offline-mesh-smoke.js
import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';

const TARGET = __ENV.TARGET || 'http://localhost:8080';
const bundle = encoding.b64decode(__ENV.BUNDLE_B64, 'std');

export const options = {
  scenarios: {
    settle: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    'http_req_duration{route:settle}': ['p(99)<15', 'p(95)<8'],
    http_req_failed: ['rate<0.001'],
    checks: ['rate>0.999'],
    dropped_iterations: ['count<1'],
  },
};

export default function () {
  const res = http.post(`${TARGET}/v1/offline/settlements`, bundle, {
    headers: { 'Content-Type': 'application/vnd.fortress.fce' },
    tags: { route: 'settle' },
  });
  check(res, {
    'settled or duplicate': (r) =>
      r.status === 200 && ['SETTLED', 'DUPLICATE'].includes(r.json('outcome')),
  });
}
