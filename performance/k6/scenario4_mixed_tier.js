import { runChatRequest } from "./common.js";

const duration = __ENV.DURATION || "60s";

export const options = {
  vus: Number(__ENV.VUS || 100),
  duration,
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<900", "p(99)<2000"]
  }
};

export default function () {
  const roll = Math.random();
  const apiKey = roll < 0.9 ? `sk-free-${__VU}` : `sk-pro-${__VU}`;
  runChatRequest(apiKey);
}
