import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("opens the health listener before waiting for Accessibility discovery", async () => {
  const source = await readFile(new URL("../src/index.mjs", import.meta.url), "utf8");
  const start = source.indexOf("const startup = service.start();");
  const listen = source.indexOf("server.listen(");
  const wait = source.indexOf("await startup;");

  assert.ok(start >= 0);
  assert.ok(listen > start);
  assert.ok(wait > listen);
});
