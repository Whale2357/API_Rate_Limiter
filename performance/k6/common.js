import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";

export const ok200 = new Counter("v6_ok_200_total");
export const reject429 = new Counter("v6_reject_429_total");
export const unauthorized401 = new Counter("v6_unauthorized_401_total");
export const successRate = new Rate("v6_success_rate");

const TARGETS = (__ENV.TARGETS || "http://localhost:8080,http://localhost:8081")
  .split(",")
  .map((v) => v.trim())
  .filter(Boolean);

export function randomTarget() {
  return TARGETS[Math.floor(Math.random() * TARGETS.length)];
}

export function runChatRequest(apiKey) {
  const url = `${randomTarget()}/v1/chat`;
  const payload = JSON.stringify({ message: "load-test" });
  const params = {
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`
    },
    tags: { endpoint: "/v1/chat" }
  };

  const res = http.post(url, payload, params);
  successRate.add(res.status === 200);

  if (res.status === 200) ok200.add(1);
  if (res.status === 429) reject429.add(1);
  if (res.status === 401) unauthorized401.add(1);

  check(res, {
    "status is 200 or 429": (r) => r.status === 200 || r.status === 429
  });

  return res;
}
