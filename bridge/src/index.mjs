import { chmodSync, rmSync } from "node:fs";

import { load } from "./config.mjs";
import { Rpc } from "./codex/rpc.mjs";
import { Catalog } from "./task/catalog.mjs";
import { Store } from "./profile/store.mjs";
import { Session } from "./control/session.mjs";
import { Desktop } from "./macos/desktop.mjs";
import { create } from "./server/http.mjs";
import { create as createAdmin } from "./server/admin.mjs";
import { Events } from "./server/events.mjs";
import { Shutdown } from "./server/shutdown.mjs";
import { Invitations } from "./pairing/invitations.mjs";
import { Credentials } from "./pairing/credentials.mjs";

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
const credentials = new Credentials({ path: config.devicesPath, rootToken: config.token });
const invitations = new Invitations({ issue: () => credentials.issue() });
const server = create({ service, events, token: config.token, credentials, invitations });
const admin = createAdmin({ invitations });

const startup = service.start();
server.listen(config.port, config.host, () => {
  console.log(`Vibe Pocket bridge listening on http://${config.host}:${config.port}`);
  console.log(`Configured workspaces: ${Object.keys(config.workspaces).join(", ")}`);
  console.log(`Controller profile: ${config.profilePath}`);
  console.log("Codex control engine: Bluetooth HID, native task links, and scoped macOS Accessibility");
});
rmSync(config.pairingSocketPath, { force: true });
admin.listen(config.pairingSocketPath, () => chmodSync(config.pairingSocketPath, 0o600));

const shutdown = new Shutdown({
  servers: [server, admin],
  service,
  events,
  cleanup: () => rmSync(config.pairingSocketPath, { force: true }),
});

function close() {
  void shutdown.close().catch((error) => {
    console.error("Vibe Pocket bridge shutdown failed:", error);
    process.exitCode = 1;
  });
}

process.once("SIGINT", close);
process.once("SIGTERM", close);

await startup;
