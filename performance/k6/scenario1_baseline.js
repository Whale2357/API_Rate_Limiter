import http from "k6/http";
import { check } from "k6";

const duration = __ENV.DURATION || "60s";
const target = __ENV.BASELINE_TARGET || "http://localhost:8080";

export const options = {
  vus: Number(__ENV.VUS || 100),
  duration,
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500", "p(99)<1000"]
  }
};

export default function () {
  const res = http.post(
    `${target}/v1/chat`,
    JSON.stringify({ message: "baseline" }),
    {
      headers: { "Content-Type": "application/json" },
      tags: { scenario: "baseline", endpoint: "/v1/chat" }
    }
  );

  check(res, {
    "baseline status is 200": (r) => r.status === 200
  });
}
