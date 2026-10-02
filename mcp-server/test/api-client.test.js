import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { once } from "node:events";
import { createApiClient } from "../src/api-client.js";

test("ERP decimals and large identifiers retain their exact source digits", async (t) => {
  const server = http.createServer((_req, res) => {
    res.setHeader("Content-Type", "application/json");
    res.end('{"quantity":999999999999.999999,"smallQuantity":0.000001,"zero":0.000000,"unitPrice":1.230000,"id":9007199254740993,"count":20,"ok":true}');
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const api = createApiClient({ base: `http://127.0.0.1:${server.address().port}`, role: "VIEWER" });
  const data = await api("/inventory");
  assert.equal(data.quantity, "999999999999.999999");
  assert.equal(data.smallQuantity, "0.000001");
  assert.equal(data.zero, "0.000000");
  assert.equal(data.unitPrice, "1.230000");
  assert.equal(data.id, "9007199254740993");
  assert.equal(data.count, 20);
  assert.equal(data.ok, true);
});
