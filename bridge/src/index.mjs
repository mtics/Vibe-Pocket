import { CodexAppServer } from "./codex-app-server.mjs";
import { CodexAppServerController } from "./codex-app-server-controller.mjs";
import { loadConfig } from "./config.mjs";
import { ControllerProfileStore } from "./controller-profile-store.mjs";
import { DesktopCodexService } from "./desktop-codex-service.mjs";
import { createPocketHttpServer } from "./http-server.mjs";
import { OwnedThreadStore } from "./owned-thread-store.mjs";
import { SseHub } from "./sse-hub.mjs";

const config = loadConfig();
const events = new SseHub();
const profileStore = new ControllerProfileStore({ profilePath: config.profilePath });
const desktop = config.engine === "app-server"
  ? new CodexAppServerController({
      appServer: new CodexAppServer({ command: config.codexCommand }),
      workspaces: config.workspaces,
      ownershipStore: new OwnedThreadStore({ path: config.ownedThreadsPath }),
    })
  : undefined;
const service = new DesktopCodexService({
  workspaces: config.workspaces,
  events,
  profileStore,
  desktop,
});
const server = createPocketHttpServer({ service, events, token: config.token });

await service.start();
server.listen(config.port, config.host, () => {
  console.log(`Vibe Pocket bridge listening on http://${config.host}:${config.port}`);
  console.log(`Configured workspaces: ${Object.keys(config.workspaces).join(", ")}`);
  console.log(`Controller profile: ${config.profilePath}`);
  console.log(`Owned Codex tasks: ${config.ownedThreadsPath}`);
  console.log(`Codex control engine: ${config.engine}`);
});

async function close() {
  events.close();
  server.close();
  await service.dispose();
}

process.once("SIGINT", close);
process.once("SIGTERM", close);
