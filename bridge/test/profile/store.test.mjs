import assert from "node:assert/strict";
import { realpathSync } from "node:fs";
import { mkdir, mkdtemp, readFile, readdir, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
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

const REPOSITORY_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

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
  assert.equal(
    load(environment).profilePath,
    join(realpathSync(dirname(environment.VIBE_POCKET_PROFILE_PATH)), basename(environment.VIBE_POCKET_PROFILE_PATH)),
  );
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

test("rejects a profile path whose symlinked parent enters the repository", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-config-link-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const linkedRepository = join(root, "linked-repository");
  await symlink(REPOSITORY_ROOT, linkedRepository, "dir");

  assert.throws(
    () => load({
      VIBE_POCKET_TOKEN: "x".repeat(24),
      VIBE_POCKET_PROFILE_PATH: join(linkedRepository, "private", "controller-profile.json"),
    }),
    /outside the Vibe Pocket repository/,
  );
  assert.throws(
    () => load({
      VIBE_POCKET_TOKEN: "x".repeat(24),
      XDG_CONFIG_HOME: linkedRepository,
    }),
    /outside the Vibe Pocket repository/,
  );
});

test("rejects an unresolved symlink parent", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-config-dangling-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const linkedDirectory = join(root, "linked-directory");
  await symlink(join(REPOSITORY_ROOT, "not-created"), linkedDirectory, "dir");

  assert.throws(
    () => load({
      VIBE_POCKET_TOKEN: "x".repeat(24),
      VIBE_POCKET_PROFILE_PATH: join(linkedDirectory, "controller-profile.json"),
    }),
    /unresolved symbolic link/,
  );
});

test("accepts a safe profile path below non-existing ancestors", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-config-missing-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const profilePath = join(root, "not-created", "nested", "controller-profile.json");

  assert.equal(load({
    VIBE_POCKET_TOKEN: "x".repeat(24),
    VIBE_POCKET_PROFILE_PATH: profilePath,
  }).profilePath, join(realpathSync(dirname(profilePath)), basename(profilePath)));
});

test("retains canonical profile and workspace paths after configured symlinks retarget", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-config-retarget-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const safeProfileRoot = join(root, "safe-profile");
  const safeWorkspace = join(root, "safe-workspace");
  const alternate = join(root, "alternate");
  const profileLink = join(root, "profile-link");
  const workspaceLink = join(root, "workspace-link");
  await mkdir(safeProfileRoot);
  await mkdir(safeWorkspace);
  await mkdir(alternate);
  await symlink(safeProfileRoot, profileLink, "dir");
  await symlink(safeWorkspace, workspaceLink, "dir");

  const config = load({
    VIBE_POCKET_TOKEN: "x".repeat(24),
    VIBE_POCKET_PROFILE_PATH: join(profileLink, "nested", "controller-profile.json"),
    VIBE_POCKET_WORKSPACES: JSON.stringify({ research: workspaceLink }),
  });
  assert.equal(config.profilePath, join(realpathSync(safeProfileRoot), "nested", "controller-profile.json"));
  assert.equal(config.workspaces.research, realpathSync(safeWorkspace));

  await rm(profileLink);
  await rm(workspaceLink);
  await symlink(alternate, profileLink, "dir");
  await symlink(alternate, workspaceLink, "dir");

  assert.equal(config.profilePath, join(realpathSync(safeProfileRoot), "nested", "controller-profile.json"));
  assert.equal(config.ownedThreadsPath, join(realpathSync(safeProfileRoot), "nested", "owned-threads.json"));
  assert.equal(config.pairingSocketPath, join(realpathSync(safeProfileRoot), "nested", "pairing.sock"));
  assert.equal(config.operationPath, join(realpathSync(safeProfileRoot), "nested", "operations.json"));
  assert.equal(config.workspaces.research, realpathSync(safeWorkspace));
});

test("does not expose a configurable desktop-control engine", () => {
  const environment = { VIBE_POCKET_TOKEN: "x".repeat(24) };
  assert.equal(Object.hasOwn(load(environment), "engine"), false);
  assert.equal(Object.hasOwn(load({ ...environment, VIBE_POCKET_ENGINE: "accessibility" }), "engine"), false);
});
