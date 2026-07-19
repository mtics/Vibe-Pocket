import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, unlink, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, posix, win32 } from "node:path";

import {
  createDefault,
  normalize,
} from "./model.mjs";

const PROFILE_FILE_NAME = "controller-profile.json";

export class Store {
  #profilePath;
  #lastLoadError = null;

  constructor({ profilePath }) {
    if (typeof profilePath !== "string" || !isAbsolute(profilePath)) {
      throw new Error("Controller profile path must be absolute.");
    }
    this.#profilePath = profilePath;
  }

  get profilePath() {
    return this.#profilePath;
  }

  get lastLoadError() {
    return this.#lastLoadError;
  }

  async load() {
    try {
      const serialized = await readFile(this.#profilePath, "utf8");
      const profile = normalize(JSON.parse(serialized));
      this.#lastLoadError = null;
      return profile;
    } catch (error) {
      this.#lastLoadError = error;
      return createDefault();
    }
  }

  async save(profile) {
    const normalized = normalize(profile);
    const directory = dirname(this.#profilePath);
    const temporaryPath = join(directory, `.${PROFILE_FILE_NAME}.${process.pid}.${randomUUID()}.tmp`);
    await mkdir(directory, { recursive: true, mode: 0o700 });

    let renamed = false;
    try {
      await writeFile(temporaryPath, `${JSON.stringify(normalized, null, 2)}\n`, {
        encoding: "utf8",
        flag: "wx",
        mode: 0o600,
      });
      await rename(temporaryPath, this.#profilePath);
      renamed = true;
      this.#lastLoadError = null;
      return normalized;
    } finally {
      if (!renamed) await unlink(temporaryPath).catch(() => {});
    }
  }
}

export function defaultPath({
  environment = process.env,
  platform = process.platform,
  homeDirectory = homedir(),
} = {}) {
  const pathApi = platform === "win32" ? win32 : posix;
  if (environment.XDG_CONFIG_HOME && pathApi.isAbsolute(environment.XDG_CONFIG_HOME)) {
    return pathApi.join(environment.XDG_CONFIG_HOME, "vibe-pocket", PROFILE_FILE_NAME);
  }
  if (platform === "win32" && environment.APPDATA && win32.isAbsolute(environment.APPDATA)) {
    return win32.join(environment.APPDATA, "Vibe Pocket", PROFILE_FILE_NAME);
  }
  if (platform === "darwin") {
    return posix.join(homeDirectory, "Library", "Application Support", "Vibe Pocket", PROFILE_FILE_NAME);
  }
  return pathApi.join(homeDirectory, ".config", "vibe-pocket", PROFILE_FILE_NAME);
}
