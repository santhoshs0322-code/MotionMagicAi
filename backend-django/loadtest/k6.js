import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: { public_reads: { executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 5000, stages: [
    { target: Number(__ENV.TARGET_RPS || 100), duration: '2m' },
    { target: Number(__ENV.TARGET_RPS || 100), duration: '5m' },
    { target: 0, duration: '1m' },
  ] } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'] },
};

export default function () {
  const base = (__ENV.BASE_URL || '').replace(/\/$/, '');
  if (!base.startsWith('https://')) throw new Error('Set an HTTPS BASE_URL for an isolated load-test environment');
  const response = http.get(`${base}/api/v1/capabilities`);
  check(response, { '200': (r) => r.status === 200, 'bounded JSON': (r) => r.body.length < 65536 });
  sleep(Math.random());
}
