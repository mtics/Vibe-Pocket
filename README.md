# Vibe Pocket

Vibe Pocket turns an Android phone into a private Codex Micro-style controller
for the visible Codex task in the ChatGPT desktop app on the M5. It controls the
same task shown on the Mac rather than starting an invisible CLI session.

## Controller

The Android 0.4 controller is driven by a versioned profile from the M5:

- Six live Agent Keys show idle, running, needs-input, complete, and error
  states. A populated key focuses that exact Agent.
- Thirteen command keys cover accept, reject, dictation, new task, stop, access
  mode, clear, focus, navigation, and foreground attachment.
- A four-way joystick starts the built-in review, debug, refactor, and test
  workflows on release.
- A stepped dial changes reasoning depth when the visible Codex composer allows
  it.
- Six programmable layers persist across bridge restarts.
- Every input has independent tap, double-tap, and hold mappings. The phone's
  mapping sheet can only select actions advertised by the bridge.

The phone never sends a prompt, transcript, terminal byte, shell command, or
arbitrary key sequence. A workflow button sends only a fixed workflow ID; the
M5 expands it into a built-in, reviewed prompt and starts it in a new visible
Codex task.

## Install The M5 Bridge

The M5 needs ChatGPT with Codex open, macOS Accessibility permission, Node.js
22 or newer, and the Swift toolchain supplied by Xcode. Install dependencies
once, then install the user LaunchAgent:

```sh
cd /Users/lizhw/Documents/Codex/2026-07-04/new-chat-3/bridge
npm install
VIBE_POCKET_TOKEN="your-existing-pairing-token" \
VIBE_POCKET_PORT=4320 \
./bin/install-launch-agent.sh
```

The installer copies the runtime to:

```text
~/Library/Application Support/Vibe Pocket/runtime
```

Open **System Settings → Privacy & Security → Accessibility**, press `+`, and
add this stable helper binary:

```text
~/Library/Application Support/Vibe Pocket/runtime/bin/vibe-pocket-codex-helper
```

This permission lets the helper click and navigate only the filtered visible
Codex controls implemented by Vibe Pocket. The installer does not modify the
macOS privacy database or accept the permission for you.

It stores the token in a mode-`0600` user config file and starts
`au.edu.uts.vibepocket.bridge` with launchd. Re-running the installer upgrades
the runtime and preserves an existing token when the token is omitted.

Check the local service with:

```sh
curl http://127.0.0.1:4320/healthz
launchctl print gui/$UID/au.edu.uts.vibepocket.bridge
```

Controller mappings are stored outside the repository at:

```text
~/Library/Application Support/Vibe Pocket/controller-profile.json
```

## Tailnet Access

Keep the bridge on localhost and expose it only through Tailscale Serve:

```sh
/Applications/Tailscale.app/Contents/MacOS/Tailscale serve \
  --https=443 --bg http://127.0.0.1:4320
```

The current M5 endpoint is:

```text
https://aaa30.tail7f2929.ts.net
```

Do not enable Tailscale Funnel. The unauthenticated `/healthz` endpoint exposes
only the service and protocol version; snapshots, events, configuration, and
commands all require the pairing token.

## Build Android

```sh
cd /Users/lizhw/Documents/Codex/2026-07-04/new-chat-3/android
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/lizhw/Library/Android/sdk \
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is:

```text
/Users/lizhw/Documents/Codex/2026-07-04/new-chat-3/android/app/build/outputs/apk/debug/app-debug.apk
```

On this LAN it is also available at:

```text
http://192.168.31.250:4319/app-debug.apk
```

The app requires an HTTPS bridge URL and stores its token with an Android
Keystore AES-GCM key. On MIUI, unrestricted battery use is optional; the SSE
connection is active only while Vibe Pocket is in the foreground.

## Security Boundary

- The bridge binds to `127.0.0.1`; Tailscale supplies tailnet-only HTTPS.
- Commands and configurable mappings use a strict semantic action whitelist.
- Workflow text exists only on the M5 and cannot be supplied by the phone.
- Desktop actions target filtered controls in the visible Codex Accessibility
  tree and use a serialized, timeout-bounded helper.
- The bridge does not expose task text, historical conversations, OpenAI
  credentials, arbitrary prompts, raw keyboard sequences, or shell execution.
- Idempotency keys are request-bound and bounded; rapid Android actions are
  additionally protected by a single-flight gate.

## Verification

- Bridge: 31 Node tests cover profile migration and persistence, all three
  gestures, action validation, layer editing, Agent focus, polling,
  idempotency, authentication, and HTTP health behavior.
- Android: 7 JVM tests cover v1/v2 parsing, structured command serialization,
  capability gating, and rapid-tap single-flight behavior.
- Android `lintDebug` and `assembleDebug` pass under Java 17.
- The M5 LaunchAgent, local health endpoint, and Tailnet HTTPS health endpoint
  are verified live.

On-device Xiaomi 13 layout, haptics, and each desktop HID action must still be
verified after installing the current APK and resolving any macOS permission
prompt shown by the M5.
