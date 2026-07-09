import { runChatRequest } from "./common.js";

const duration = __ENV.DURATION || "60s";

export const options = {
  vus: Number(__ENV.VUS || 100),
  duration,
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000", "p(99)<2500"]
  }
};

export default function () {
  runChatRequest("sk-userA");
}
