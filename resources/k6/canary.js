import http from "k6/http";
import { check, sleep } from "k6";
const service = __ENV.SERVICE_NAME || "order";
const target = __ENV.TARGET_URL || "localhost";
const host = __ENV.K6_HOST || "dev.go-micro.local";
const params = { headers: { Host: host, "Content-Type": "application/json" } };
export const options = {
  stages: [
    { duration: "5s", target: 50 },
    { duration: "5s", target: 100 },
    { duration: "10s", target: 200 },
  ],
  thresholds: { http_req_failed: ["rate<0.05"] },
};
function ok(r) { return r.status === 200 || r.status === 201; }
export default function () {
  const base = "http://" + target;
  const id = __VU + "-" + __ITER;
  check(http.get(base + ({ product:"/api/v1/products", order:"/api/v1/orders", inventory:"/api/v1/inventory", noti:"/api/v1/notifications", payment:"/api/v1/payments/order/1", client:"/" }[service] || "/api/v1/products"), params), { "get 200": (r) => r.status === 200 });
  if (service === "product") { check(http.post(base + "/api/v1/products", JSON.stringify({ name: "k6-" + id, description: "canary", price: 1 }), params), { "write 2xx": ok }); }
  else if (service === "inventory") { check(http.post(base + "/api/v1/inventory", JSON.stringify({ product_id: 1, quantity: 1, sku: "k6-" + id, location: "k6" }), params), { "write 2xx": ok }); }
  else if (service === "noti") { check(http.post(base + "/api/v1/notifications", JSON.stringify({ order_id: 1, customer_id: 1, message: "k6-" + id, status: "pending" }), params), { "write 2xx": ok }); }
  sleep(0.3);
}
