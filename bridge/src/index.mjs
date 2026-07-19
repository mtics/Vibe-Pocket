import { load } from "./config.mjs";
import { Rpc } from "./codex/rpc.mjs";
import { Catalog } from "./task/catalog.mjs";
import { Store } from "./profile/store.mjs";
import { Session } from "./control/session.mjs";
import { Desktop } from "./macos/desktop.mjs";
import { create } from "./server/http.mjs";
import { Events } from "./server/events.mjs";

const config = load();
const events = new Events();
const profileStore = new Store({ profilePath: config.profilePath });
const appServer = new Rpc({ command: config.codexCommand });
appServer.on("serverRequest", (message) => {
  appServer.respondWithError(message.id, -32601, "Vibe Pocket's task catalog is read-only.");
});
const threadCatalog = new Catalog({ appServer });
const desktop = new Desktop({ threadCatalog });
const service = new Session({
  workspaces: config.workspaces,
  events,
  profileStore,
  desktop,
});
const server = create({ service, events, token: config.token });

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
