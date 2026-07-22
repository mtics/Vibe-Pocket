let serialized = "";
for await (const chunk of process.stdin) {
  serialized += chunk;
  if (serialized.length > 16_384) invalid();
}

let document;
try {
  document = JSON.parse(serialized);
} catch {
  invalid();
}

if (!document || typeof document !== "object" || Array.isArray(document)) invalid();
if (typeof document.pairingUrl !== "string" || document.pairingUrl.length > 2_048) invalid();
if (typeof document.expiresAt !== "string" || !Number.isFinite(Date.parse(document.expiresAt))) invalid();

let pairingUrl;
let origin;
try {
  pairingUrl = new URL(document.pairingUrl);
  origin = new URL(pairingUrl.searchParams.get("origin"));
} catch {
  invalid();
}

const code = pairingUrl.searchParams.get("code") ?? "";
if (
  pairingUrl.protocol !== "vibepocket:"
  || pairingUrl.hostname !== "pair"
  || pairingUrl.pathname !== ""
  || pairingUrl.username
  || pairingUrl.password
  || pairingUrl.hash
  || pairingUrl.toString() !== document.pairingUrl
  || origin.protocol !== "https:"
  || !origin.hostname
  || origin.username
  || origin.password
  || origin.pathname !== "/"
  || origin.search
  || origin.hash
  || !/^[A-Za-z0-9_-]{32,128}$/.test(code)
) {
  invalid();
}

const deepLink = quoteForShell(pairingUrl.toString());
process.stdout.write(
  [
    "if pm path au.edu.uts.vibepocket >/dev/null 2>&1; then",
    `  exec am start -W -n au.edu.uts.vibepocket/.MainActivity -a android.intent.action.VIEW -d ${deepLink}`,
    "fi",
    "if pm path au.edu.uts.vibepocket.research >/dev/null 2>&1; then",
    `  exec am start -W -n au.edu.uts.vibepocket.research/au.edu.uts.vibepocket.MainActivity -a android.intent.action.VIEW -d ${deepLink}`,
    "fi",
    "echo 'Vibe Pocket is not installed.' >&2",
    "exit 1",
    "",
  ].join("\n"),
);

function quoteForShell(value) {
  return `'${value.replaceAll("'", "'\\''")}'`;
}

function invalid() {
  process.stderr.write("The Bridge returned an invalid pairing invitation.\n");
  process.exit(1);
}
