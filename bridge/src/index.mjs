import { loadConfig } from "./config.mjs";
import { CodexAppServer } from "./codex-app-server.mjs";
import { CodexThreadCatalog } from "./codex-thread-catalog.mjs";
import { ControllerProfileStore } from "./controller-profile-store.mjs";
import { DesktopCodexService } from "./desktop-codex-service.mjs";
import { MacCodexDesktopController } from "./macos-codex-desktop.mjs";
import { createPocketHttpServer } from "./http-server.mjs";
import { SseHub } from "./sse-hub.mjs";

const config = loadConfig();
const events = new SseHub();
const profileStore = new ControllerProfileStore({ profilePath: config.profilePath });
const appServer = new CodexAppServer({ command: config.codexCommand });
appServer.on("serverRequest", (message) => {
  appServer.respondWithError(message.id, -32601, "Vibe Pocket's task catalog is read-only.");
});
const threadCatalog = new CodexThreadCatalog({ appServer });
const desktop = new MacCodexDesktopController({ threadCatalog });
const service = new DesktopCodexService({
  workspaces: config.workspaces,
  events,
  profileStore,
  desktop,
});
const server = createPocketHttpServer({ service, events, token: config.token });

const startup = service.start();
server.listen(config.port, config.host, () => {
  console.log(`Vibe Pocket bridge listening on http://${config.host}:${config.port}`);
  console.log(`Configured workspaces: ${Object.keys(config.workspaces).join(", ")}`);
  console.log(`Controller profile: ${config.profilePath}`);
  console.log("Codex control engine: Bluetooth HID, native task links, and scoped macOS Accessibility");
});

async function close() {
  events.close();
  server.close();
  await service.dispose();
}

process.once("SIGINT", close);
process.once("SIGTERM", close);

await startup;
