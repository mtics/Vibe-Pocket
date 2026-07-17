import { loadConfig } from "./config.mjs";
import { DesktopCodexService } from "./desktop-codex-service.mjs";
import { createPocketHttpServer } from "./http-server.mjs";
import { SseHub } from "./sse-hub.mjs";

const config = loadConfig();
const events = new SseHub();
const service = new DesktopCodexService({
  workspaces: config.workspaces,
  events,
});
const server = createPocketHttpServer({ service, events, token: config.token });

await service.start();
server.listen(config.port, config.host, () => {
  console.log(`Vibe Pocket bridge listening on http://${config.host}:${config.port}`);
  console.log(`Configured workspaces: ${Object.keys(config.workspaces).join(", ")}`);
});

async function close() {
  events.close();
  server.close();
  await service.dispose();
}

process.once("SIGINT", close);
process.once("SIGTERM", close);
