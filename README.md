# Vibe Pocket

Vibe Pocket turns an Android phone into a private Codex Micro-style controller
for Codex tasks owned through the local `codex app-server` protocol on the M5.
It controls Codex threads and turns directly rather than simulating desktop UI.

## Controller

The Android 0.6.0 controller uses protocol v5 and a versioned profile from the
M5:

- Six live Agent Keys distinguish idle, unread, thinking, running,
  needs-input, complete, and error states. A populated key focuses that exact
  Agent by an opaque stable ID rather than by its current list position.
- Thirteen command keys cover accept/submit, reject/dismiss, phone dictation,
  new task, stop, collaboration mode, clear, Agent focus, navigation, and task
  resume. Mode cycles Codex Default and Plan; access permissions are a separate
  remappable action.
  Accept resolves a pending Codex approval first and otherwise submits the
  focused task's dictation draft. Reject declines or discards the same intent.
- A Voice-mapped input is true push-to-talk: pointer down starts phone speech
  recognition and release finalizes an in-memory draft. Leaving the foreground,
  disconnecting, or closing the ViewModel cancels recognition and queues a
  best-effort Voice Stop state.
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
  Accept answers through JSON-RPC, and Reject dismisses it. Voice supplies a
  free-form answer when a question has no choices.
- Every input has independent tap, double-tap, and hold mappings. Push-to-talk
  is deliberately exclusive on its input because a held recording gesture
  cannot also have an unambiguous hold action. The phone's mapping sheet can
  only select actions advertised by the bridge.

The phone sends only whitelisted controller actions, bounded configuration
updates, and a final system speech recognition result. Dictation remains an
in-memory draft until Accept submits it or Clear/Reject discards it. A workflow
button sends only a fixed workflow ID; the M5 expands it from the persisted
profile and starts a new Codex app-server task.

Vibe Pocket owns its Codex tasks through a private local registry. It does not
attach to or manipulate whichever ChatGPT task happens to be visible on the
desktop. Codex can still use its normal tools inside the selected workspace and
access mode.

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

The default `app-server` engine does not request macOS Accessibility permission
and remains available while the M5 is locked. The former UI-control engine is
kept only as an explicit compatibility option via
`VIBE_POCKET_ENGINE=accessibility`.

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
connection is active only while Vibe Pocket is in the foreground. Android 12+
prefers on-device speech recognition when available; otherwise the configured
system recognition service may process audio remotely.

## First Controller Test

1. Keep the M5 online; ChatGPT may remain closed or in the background.
2. Open Vibe Pocket and tap Refresh. The status card should turn green and say
   `Ready`.
3. Tap New task or select one of the six live Agent keys.
4. Press and hold Voice, speak, then release. Accept submits the in-memory
   dictation to the focused Codex task; Clear discards it.
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
- Protocol v5 binds Agent focus to a stable opaque ID, models dictation as
  idempotent start/stop target states, accepts one bounded final transcript,
  and carries a bounded view of pending Codex questions.
- Workflow text can be edited by an authenticated phone, is strictly bounded,
  and is persisted only in the M5 profile. Normal workflow presses send only an
  ID.
- The default path uses Codex app-server JSON-RPC and requires no macOS
  Accessibility permission or foreground ChatGPT window.
- The bridge does not expose task text, historical conversations, OpenAI
  credentials, raw keyboard sequences, or direct shell execution endpoints.
- Phone transcripts are bounded, held only in memory, bound to one owned task,
  hashed in the idempotency cache, and submitted only after explicit Accept.
- The owned-task registry contains only opaque thread IDs and is mode `0600`.
- Idempotency keys are request-bound and bounded; rapid Android actions are
  additionally protected by a single-flight gate.
- The LaunchAgent starts Node with a minimal environment so unrelated user
  session credentials are not inherited by the bridge process.

## Verification

- Bridge: 51 Node tests cover profile and owned-task persistence, app-server
  lifecycle races, permission schemas, modes, reasoning, stop, workflows,
  Codex user-input requests, gestures, layer switching, Agent focus, polling,
  idempotency, authentication, and HTTP health.
- Android JVM tests cover profile parsing, protocol v5 data, structured
  command serialization, capability gating, ordered PTT delivery, both speech
  callback orders, stale callbacks, and lifecycle/disconnect cleanup.
- Android `lintDebug` and `assembleDebug` pass under Java 17.
- The M5 LaunchAgent, local health endpoint, and Tailnet HTTPS health endpoint
  are verified live.

Direct app-server task creation, dictation submission, natural completion,
collaboration/access/reasoning updates, interruption, and a real Plan-mode
`request_user_input` selection/answer round trip are verified live on the M5.
Xiaomi 13 speech-provider behavior and every physical gesture still require an
on-device pass after installing the current APK.
