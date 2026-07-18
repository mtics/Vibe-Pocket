# Vibe Pocket

Vibe Pocket turns an Android phone into a private Codex Micro-style controller.
It combines a Bluetooth HID keyboard for stable frontmost shortcuts with a
local M5 bridge that discovers the visible Codex task and performs narrowly
scoped semantic operations.

## Controller

The Android 0.7.5 controller uses protocol v5 and a versioned profile from the
M5:

- Six live Agent Keys distinguish idle, unread, thinking, running,
  needs-input, complete, and error states. A populated key focuses that exact
  Agent by an opaque stable ID rather than by its current list position.
- Thirteen command keys cover accept/submit, reject/dismiss, Codex voice input,
  new task, stop, collaboration mode, clear, Agent focus, navigation, and task
  resume. Mode cycles Codex Default and Plan; access permissions are a separate
  remappable action.
  Accept resolves a visible Codex approval first and otherwise submits a
  non-empty visible composer draft. Reject is enabled only for an explicit
  visible rejection control.
- When the Mac is connected as a Bluetooth keyboard and ChatGPT is confirmed
  frontmost, Accept, Reject, Stop, Mode, Reasoning, Voice, and navigation use
  fixed HID chords. Direction keys repeat while held. Custom mappings that
  carry structured user input continue through the authenticated Bridge.
- Agent focus uses a read-only `codex app-server` task catalog and the native
  `codex://threads/<id>` link. The catalog never resumes or modifies a task,
  and macOS opens the link in the background without activating Codex. Native
  task navigation does not wait behind an Accessibility status scan.
- New task, Clear, Access, and workflows use narrow Bridge operations. Clear
  writes only the visible Codex composer's AX value. Access uses a semantic AX
  press and reports unavailable when macOS cannot expose the menu reliably; it
  never falls back to moving the pointer.
- Voice is push-to-talk for the visible ChatGPT Codex dictation control. Hold
  the key to hold ChatGPT's own dictation shortcut and release it to send a
  zero-key HID report. The Bridge uses the same semantic control only when HID
  is unavailable.
- A four-way joystick creates a visible Codex task and submits the built-in
  review, debug, refactor, or test workflow on release.
- Rotate the center dial clockwise or counterclockwise to change the visible
  Codex reasoning selector by one verified UI step for every quarter turn.
  Structural control discovery is independent from localized level labels.
  The adjacent minus and plus keys expose directional capability separately,
  so the minimum disables only minus and the maximum disables only plus.
- Six programmable layers persist across bridge restarts. Hold the phone's L1
  control with Accept, Reject, Voice, New task, Up, or Down to select layers
  1 through 6, respectively. A 750 ms guard prevents the layer chord from
  leaking into a newly selected mapping.
- A mapped input can switch directly to any of the six layers. Layer names,
  colors, and the four workflow prompts are editable on the phone and persist
  in the M5 profile.
- When Codex exposes an approval or choice control in the visible task, the
  phone's Accept, Reject, and navigation actions operate that same interface.
- Every input has independent tap, double-tap, and hold mappings. The phone's
  mapping sheet can only select actions advertised by the bridge.

The phone sends only fixed keyboard chords or whitelisted controller actions
and bounded configuration updates. It does not expose a raw-keyboard or shell
endpoint on the M5. A workflow button sends only a fixed workflow ID; the M5
expands it from the persisted profile and starts a new visible Codex task.

Input execution is split into three functional units. The planner resolves a
profile gesture and checks its current capability. The orchestrator chooses a
preferred HID tap or hold with an explicit Bridge fallback and owns push-to-talk
release. The HID and Bridge transports contain only their respective side
effects. Compose renders controls and feedback without deciding transport
policy.

Bluetooth HID is Vibe Pocket's supported virtual-hardware transport. The stock
Android app does not claim USB-C HID keyboard mode: Android's public USB model
documents host and Android Accessory modes, not a generic application-provided
USB keyboard-gadget profile. A USB-C cable can still charge the phone or carry
ADB/accessory traffic, but it is not a replacement for the Bluetooth HID link.

Visible command keys operate the Codex task currently shown on the M5. The
Bridge resolves visible task labels uniquely against Codex's read-only task
catalog, omits workspace or ambiguous rows, and derives stable opaque Agent IDs
from the real task IDs.
`vibe-pocket-attach` is an optional shortcut for opening a known desktop task
by ID before focusing its composer.

## Install The M5 Bridge

The M5 needs the Codex CLI, Node.js 22 or newer, a signed-in Codex account, and
the Codex view in ChatGPT. Visible controls require an unlocked user session;
ordinary controller actions never activate ChatGPT or steal focus. Only the
explicit Attach action opens and activates a task. Install dependencies once,
then install the user LaunchAgent:

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

The bridge uses a narrowly scoped Swift Accessibility driver compiled into the
signed Bridge Host. It reads the visible Codex structure and performs
whitelisted AXPress or AXValue operations only where Codex has no native task
link or installed shortcut. Mode and Reasoning use installed Codex semantic
shortcuts, while Agent focus uses real task IDs and background native links.
The helper never synthesizes pointer movement. Node handles authenticated phone
commands, a read-only task catalog, controller profiles, and event delivery
without creating or resuming a hidden Codex session.
The health listener starts before the first Accessibility discovery pass, so a
slow or unusually large Codex window cannot make launchd installation look
unhealthy; the authenticated controller snapshot remains `starting` until that
pass completes.

After installation, add **Vibe Pocket Bridge Host** once under **System
Settings > Privacy & Security > Accessibility**. The background service checks
this permission silently and never opens repeated authorization prompts. Visible
window controls require an unlocked user session and an open Codex desktop view.

It stores the token in a mode-`0600` user config file and starts
`au.edu.uts.vibepocket.bridge` with launchd. Re-running the installer upgrades
the runtime and preserves an existing token when the token is omitted.

Check the local service with:

```sh
curl http://127.0.0.1:4320/healthz
launchctl print gui/$UID/au.edu.uts.vibepocket.bridge
```

To open and focus a specific task by its `CODEX_THREAD_ID`, optionally run:

```sh
vibe-pocket-attach
```

The command reads that task's ID, opens the matching Codex desktop page, and
focuses its composer. It is not required when the intended task is already
visible.

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
connection and Android HID registration are active while Vibe Pocket is in the
foreground. Android 12+ asks for Nearby devices access. Vibe Pocket does not
request microphone, Bluetooth scanning, or location access.

## First Controller Test

1. Keep the M5 online with an idle Codex task visible.
2. Open Vibe Pocket, tap Pair, and allow Nearby devices. In macOS Bluetooth
   settings, pair the Xiaomi 13. When exactly one paired device is classified
   as a computer, Vibe Pocket selects it automatically; otherwise tap its Mac
   entry in Vibe Pocket.
3. In Virtual hardware, tap the M5 host and wait for the band to say connected.
   Open an idle Codex task and test arrows, Clear, Mode, Reasoning, New task,
   and push-to-talk Voice. Drag around the center reasoning dial: each clockwise
   or counterclockwise quarter turn changes one available reasoning level. Hold
   a default direction key to repeat navigation.
4. Select a visible Agent key, or tap New task.
5. Test Agent navigation, collaboration mode, reasoning, and workflows.
   Workflow directions deliberately create and submit a new visible task.
6. Hold L1 with Accept, Reject, Voice, New task, Up, or Down to switch to
   layers 1 through 6. Open the Settings icon to edit mappings, layer colors,
   and workflow prompts.
   Map any gesture to `Select layer 1` through `Select layer 6` to make layer
   switching part of the controller surface. `Next access level` independently
   cycles Read only, Workspace, and Full access.

Vibe Pocket does not mirror task contents onto the phone. Agent keys expose
only a bounded task label and state; task execution remains inside Codex.

## Security Boundary

- The bridge binds to `127.0.0.1`; Tailscale supplies tailnet-only HTTPS.
- Commands and configurable mappings use a strict semantic action whitelist.
- Protocol v5 binds Agent focus to a stable opaque ID.
- Workflow text can be edited by an authenticated phone, is strictly bounded,
  and is persisted only in the M5 profile. Normal workflow presses send only an
  ID.
- Fixed frontmost keyboard actions use Bluetooth HID. Remaining visible task
  commands use a whitelist of AXPress and AXValue operations inside the signed
  Bridge Host. The helper never moves the pointer, and only explicit Attach may
  activate ChatGPT.
- The bridge does not expose task text, historical conversations, OpenAI
  credentials, raw keyboard sequences, or direct shell execution endpoints.
- Android delegates Bluetooth pairing keys and trust decisions to the platform;
  Vibe Pocket stores only the user-selected host address in private app storage
  so it can restore that explicit HID connection after an app or Bluetooth restart.
  With no stored choice, it may select one uniquely classified, already paired
  computer; it never guesses among multiple computers or non-computer devices.
- Idempotency keys are request-bound and bounded; duplicate rapid presses on
  the same Android control are suppressed while Bridge desktop actions remain
  ordered.
- The LaunchAgent starts Node with a minimal environment so unrelated user
  session credentials are not inherited by the bridge process.

## Verification

- Bridge: 90 Node tests cover native task-link routing, semantic Accessibility
  routing, controller
  profiles, desktop task focusing, compatibility modules, permission schemas,
  modes, reasoning, stop, workflows, gestures, layer switching, Agent focus,
  polling, idempotency, authentication, and HTTP health.
- Android JVM tests cover profile parsing, protocol v5 data, structured command
  serialization, capability gating, input planning, HID-to-Bridge fallback,
  push-to-talk ownership, HID boot-keyboard reports, repeat policy, fixed Codex
  key mappings, and Bridge-only semantic actions.
- Android `lintDebug` and `assembleDebug` pass under Java 17.
- The M5 LaunchAgent, local health endpoint, and Tailnet HTTPS health endpoint
  are verified live.

The Swift host compiles successfully and its read-only status probe resolves the
current visible Codex controls and reasoning label. Bluetooth pairing and every
physical gesture require an on-device pass after installing the current APK.
