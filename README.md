# Vibe Pocket

Vibe Pocket turns an Android phone into a private Codex Micro-style controller.
It combines a Bluetooth HID keyboard for visible-window controls with a local
M5 bridge for Codex task, Agent, workflow, access, and reasoning operations.

## Controller

The Android 0.7.0 controller uses protocol v5 and a versioned profile from the
M5:

- Six live Agent Keys distinguish idle, unread, thinking, running,
  needs-input, complete, and error states. A populated key focuses that exact
  Agent by an opaque stable ID rather than by its current list position.
- Thirteen command keys cover accept/submit, reject/dismiss, Codex voice input,
  new task, stop, collaboration mode, clear, Agent focus, navigation, and task
  resume. Mode cycles Codex Default and Plan; access permissions are a separate
  remappable action.
  Accept resolves a pending Codex approval first and otherwise submits the
  focused task's dictation draft. Reject declines or discards the same intent.
- Return, Escape, arrows, Clear, Mode, and Voice use Bluetooth keyboard reports
  when a Mac is connected. Voice emits Codex's Ctrl+Shift+D shortcut once per
  tap. Each action automatically falls back to the authenticated Bridge when
  HID is unavailable.
- A four-way joystick starts the built-in review, debug, refactor, and test
  workflows on release.
- A stepped dial writes the focused thread's reasoning effort through
  `thread/settings/update`.
- Six programmable layers persist across bridge restarts.
- A mapped input can switch directly to any of the six layers. Layer names,
  colors, and the four workflow prompts are editable on the phone and persist
  in the M5 profile.
- When Codex requests user input, the question and bounded choices appear on
  the phone. Left/right changes question, up/down changes the selected option,
  Accept answers through JSON-RPC, and Reject dismisses it.
- Every input has independent tap, double-tap, and hold mappings. The phone's
  mapping sheet can only select actions advertised by the bridge.

The phone sends only fixed keyboard chords or whitelisted controller actions
and bounded configuration updates. It does not expose a raw-keyboard or shell
endpoint on the M5. A workflow button sends only a fixed workflow ID; the M5
expands it from the persisted profile and starts a new Codex app-server task.

Vibe Pocket tracks authorized Codex tasks through a private local registry. It
can create a dedicated task or explicitly attach the current Codex Desktop task
with `vibe-pocket-attach`. It never guesses the visible task from window focus.
Codex can still use its normal tools inside the selected workspace and access
mode.

## Install The M5 Bridge

The M5 needs the Codex CLI, Node.js 22 or newer, and a signed-in Codex account.
ChatGPT does not need to remain open or in the foreground. Install dependencies
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

The bridge combines the Codex `app-server` JSON-RPC protocol with a narrowly
scoped macOS Accessibility driver. Task creation, Agent focus, lifecycle,
access, and reasoning use structured app-server calls. Return, Escape,
direction keys, composer clearing, dictation, and Shift+Tab are sent to the
visible Codex window after activating bundle `com.openai.codex`.

On the first visible-window action, macOS may ask whether **Vibe Pocket Bridge
Host** can control the computer. Allow that fixed background host under **System
Settings > Privacy & Security > Accessibility**. Visible
window controls require an unlocked user session; app-server lifecycle and
status tracking continue without a foreground window.

It stores the token in a mode-`0600` user config file and starts
`au.edu.uts.vibepocket.bridge` with launchd. Re-running the installer upgrades
the runtime and preserves an existing token when the token is omitted.

Check the local service with:

```sh
curl http://127.0.0.1:4320/healthz
launchctl print gui/$UID/au.edu.uts.vibepocket.bridge
```

From the Codex Desktop task that should receive phone controls, run:

```sh
vibe-pocket-attach
```

The command reads that task's `CODEX_THREAD_ID`, verifies that the task belongs
to a configured workspace, persists only its opaque ID, and focuses it through
Codex app-server. Run it once in each existing desktop task that should appear
on an Agent key; tasks created by Vibe Pocket are registered automatically.

Controller mappings are stored outside the repository at:

```text
~/Library/Application Support/Vibe Pocket/controller-profile.json
```

Opaque IDs for tasks created by Vibe Pocket are stored separately at:

```text
~/Library/Application Support/Vibe Pocket/owned-threads.json
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
connection and Android HID registration are active while Vibe Pocket is in the
foreground. Android 12+ asks for Nearby devices access; Vibe Pocket does not
request Bluetooth scanning, location, or microphone access.

## First Controller Test

1. Keep the M5 online and run `vibe-pocket-attach` in the visible Codex task.
2. Open Vibe Pocket, tap Pair, and allow Nearby devices. In macOS Bluetooth
   settings, pair the Xiaomi 13, then tap its Mac entry in Vibe Pocket.
3. Confirm the Virtual hardware band says connected. Open Codex and test Return,
   Escape, arrows, Clear, Mode, and Voice.
4. Select the attached task on one of the six live Agent keys, or tap New task.
5. Test Agent navigation, collaboration mode, reasoning, and workflows. Workflow
   directions deliberately create a new app-server task.
6. Open the Settings icon to edit mappings, layer colors, and workflow prompts.
   Map any gesture to `Select layer 1` through `Select layer 6` to make layer
   switching part of the controller surface. `Next access level` independently
   cycles Read only, Workspace, and Full access.

Vibe Pocket does not mirror task contents onto the phone. Agent keys expose
only a bounded task label and state; task execution remains inside Codex.

## Security Boundary

- The bridge binds to `127.0.0.1`; Tailscale supplies tailnet-only HTTPS.
- Commands and configurable mappings use a strict semantic action whitelist.
- Protocol v5 binds Agent focus to a stable opaque ID and carries a bounded view
  of pending Codex questions.
- Workflow text can be edited by an authenticated phone, is strictly bounded,
  and is persisted only in the M5 profile. Normal workflow presses send only an
  ID.
- Structured task controls use Codex app-server JSON-RPC. Fixed composer and
  navigation controls use a whitelist of macOS Accessibility keystrokes and
  require an unlocked foreground Codex window.
- The bridge does not expose task text, historical conversations, OpenAI
  credentials, raw keyboard sequences, or direct shell execution endpoints.
- Android delegates Bluetooth pairing keys and trust decisions to the platform;
  Vibe Pocket stores only the selected host address in process memory.
- The owned-task registry contains only opaque thread IDs and is mode `0600`.
- Idempotency keys are request-bound and bounded; rapid Android actions are
  additionally protected by a single-flight gate.
- The LaunchAgent starts Node with a minimal environment so unrelated user
  session credentials are not inherited by the bridge process.

## Verification

- Bridge: 65 Node tests cover the fixed Accessibility command whitelist,
  hybrid visible/task routing, profile and authorized-task persistence, explicit
  desktop task attachment, app-server
  lifecycle races, permission schemas, modes, reasoning, stop, workflows,
  Codex user-input requests, gestures, layer switching, Agent focus, polling,
  idempotency, authentication, and HTTP health.
- Android JVM tests cover profile parsing, protocol v5 data, structured command
  serialization, capability gating, HID boot-keyboard reports, fixed Codex key
  mappings, and Bridge-only semantic actions.
- Android `lintDebug` and `assembleDebug` pass under Java 17.
- The M5 LaunchAgent, local health endpoint, and Tailnet HTTPS health endpoint
  are verified live.

Direct app-server task creation, natural completion,
collaboration/access/reasoning updates, interruption, and a real Plan-mode
`request_user_input` selection/answer round trip are verified live on the M5.
Bluetooth pairing and every physical gesture require an on-device pass after
installing the current APK.
