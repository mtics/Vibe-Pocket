import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  createDefault,
  renameLayer,
  updateBinding,
} from "../../src/profile/model.mjs";
import {
  Store,
  defaultPath,
} from "../../src/profile/store.mjs";
import { load } from "../../src/config.mjs";

async function temporaryProfile(t) {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-profile-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  return { root, profilePath: join(root, "nested", "controller-profile.json") };
}

test("persists a canonical profile atomically and reloads it in a new store", async (t) => {
  const { root, profilePath } = await temporaryProfile(t);
  const store = new Store({ profilePath });
  let profile = renameLayer(createDefault(), {
    layerId: "layer-3",
    name: "Research",
  });
  profile = updateBinding(profile, {
    layerId: "layer-3",
    inputId: "key_voice",
    gesture: "hold",
    action: { type: "workflow", workflowId: "debug" },
  });

  await store.save(profile);
  const reloaded = await new Store({ profilePath }).load();
  assert.equal(reloaded.layers[2].name, "Research");
  assert.deepEqual(reloaded.layers[2].bindings.key_voice.hold, {
    type: "workflow",
    workflowId: "debug",
  });
  assert.deepEqual(await readdir(join(root, "nested")), ["controller-profile.json"]);
  const serialized = await readFile(profilePath, "utf8");
  assert.doesNotThrow(() => JSON.parse(serialized));
});

test("falls back to complete defaults without overwriting a malformed profile", async (t) => {
  const { root, profilePath } = await temporaryProfile(t);
  await mkdir(join(root, "nested"), { recursive: true });
  await writeFile(profilePath, "{ definitely not valid JSON");

  const store = new Store({ profilePath });
  const loaded = await store.load();
  assert.equal(loaded.layers.length, 6);
  assert.deepEqual(loaded.layers[0].bindings.key_voice.tap, { type: "voice" });
  assert.ok(store.lastLoadError);
  assert.equal(await readFile(profilePath, "utf8"), "{ definitely not valid JSON");
});

test("chooses an OS user configuration path and never accepts a relative store path", () => {
  assert.equal(
    defaultPath({ environment: {}, platform: "darwin", homeDirectory: "/Users/tester" }),
    "/Users/tester/Library/Application Support/Vibe Pocket/controller-profile.json",
  );
  assert.equal(
    defaultPath({ environment: { XDG_CONFIG_HOME: "/var/config" }, platform: "linux", homeDirectory: "/home/tester" }),
    "/var/config/vibe-pocket/controller-profile.json",
  );
  assert.equal(
    defaultPath({ environment: { XDG_CONFIG_HOME: "repo-config" }, platform: "linux", homeDirectory: "/home/tester" }),
    "/home/tester/.config/vibe-pocket/controller-profile.json",
  );
  assert.throws(
    () => new Store({ profilePath: "./controller-profile.json" }),
    /absolute/,
  );
});

test("loads an environment-configured profile path only outside the repository", () => {
  const environment = {
    VIBE_POCKET_TOKEN: "x".repeat(24),
    VIBE_POCKET_PROFILE_PATH: "/tmp/vibe-pocket-test/controller-profile.json",
  };
  assert.equal(load(environment).profilePath, environment.VIBE_POCKET_PROFILE_PATH);
  assert.throws(
    () => load({ ...environment, VIBE_POCKET_PROFILE_PATH: "./controller-profile.json" }),
    /absolute path outside/,
  );
  assert.throws(
    () => load({
      ...environment,
      VIBE_POCKET_PROFILE_PATH: join(process.cwd(), "controller-profile.json"),
    }),
    /outside the Vibe Pocket repository/,
  );
});

test("does not expose a configurable desktop-control engine", () => {
  const environment = { VIBE_POCKET_TOKEN: "x".repeat(24) };
  assert.equal(Object.hasOwn(load(environment), "engine"), false);
  assert.equal(Object.hasOwn(load({ ...environment, VIBE_POCKET_ENGINE: "accessibility" }), "engine"), false);
});
