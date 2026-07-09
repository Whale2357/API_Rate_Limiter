import { runChatRequest } from "./common.js";

const duration = __ENV.DURATION || "60s";

export const options = {
  vus: Number(__ENV.VUS || 100),
  duration,
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<700", "p(99)<1500"],
    v6_success_rate: ["rate>0.7"]
  }
};

export default function () {
  const userId = ((__VU - 1) % 100) + 1;
  runChatRequest(`sk-user-${userId}`);
}
