import { loadConfig } from "./config.mjs";
import { ControllerProfileStore } from "./controller-profile-store.mjs";
import { DesktopCodexService } from "./desktop-codex-service.mjs";
import { createPocketHttpServer } from "./http-server.mjs";
import { SseHub } from "./sse-hub.mjs";

const config = loadConfig();
const events = new SseHub();
const profileStore = new ControllerProfileStore({ profilePath: config.profilePath });
const service = new DesktopCodexService({
  workspaces: config.workspaces,
  events,
  profileStore,
});
const server = createPocketHttpServer({ service, events, token: config.token });

await service.start();
server.listen(config.port, config.host, () => {
  console.log(`Vibe Pocket bridge listening on http://${config.host}:${config.port}`);
  console.log(`Configured workspaces: ${Object.keys(config.workspaces).join(", ")}`);
  console.log(`Controller profile: ${config.profilePath}`);
});

async function close() {
  events.close();
  server.close();
  await service.dispose();
}

process.once("SIGINT", close);
process.once("SIGTERM", close);
