# Vibe Pocket

Vibe Pocket turns an Android phone into a private, handheld controller for the
visible Codex task in the ChatGPT desktop app on your M5. It deliberately binds
to the same desktop task you are looking at rather than starting an invisible
CLI session.

## What the Phone Controls

The Bridge validates the `Codex` view through macOS Accessibility, then presses
only fixed controls that are currently exposed by that visible view. The phone
can then:

- Bring the visible Codex task on the M5 to the foreground.
- Toggle Codex's own desktop Dictation control, using the M5 microphone.
- Stop a running Codex response through Codex's own Stop button.
- Create a new visible desktop task when explicitly requested.
- Approve or reject a visible Codex approval only while that exact control is
  present.

The phone never sends prompts, transcripts, workflows, terminal bytes, or task
content. The Mac desktop remains the authoritative place to inspect and edit a
task.

## Run the M5 Bridge

The M5 needs ChatGPT with the Codex view open, macOS Accessibility permission
for the Bridge host, Node.js 22 or newer, and the Swift toolchain already
provided by macOS/Xcode. From this checkout, install dependencies once and
start the Bridge:

```sh
cd /Users/lizhw/Documents/Codex/2026-07-04/new-chat-3/bridge
npm install
export VIBE_POCKET_TOKEN="$(openssl rand -hex 32)"
export VIBE_POCKET_WORKSPACES='{"workspace":"/absolute/path/to/allowed/workspace"}'
npm start
```

The workspace aliases are retained for protocol compatibility with the Android
client. They do not choose a local path or create a CLI process: Vibe Pocket
always controls the Codex task that is currently visible in ChatGPT.

## Private Phone Access

Keep the Bridge on localhost and expose it only inside your tailnet:

```sh
/Applications/Tailscale.app/Contents/MacOS/Tailscale serve --https=443 --bg http://127.0.0.1:4318
/Applications/Tailscale.app/Contents/MacOS/Tailscale serve status
```

Enter the HTTPS URL shown by `serve status` and the generated token in the
Android app. Do not use Tailscale Funnel for the Bridge. Disable access with:

```sh
/Applications/Tailscale.app/Contents/MacOS/Tailscale serve --https=443 off
```

## Build and Install the Android App

```sh
cd /Users/lizhw/Documents/Codex/2026-07-04/new-chat-3/android
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/lizhw/Library/Android/sdk \
./gradlew assembleDebug
```

The debug APK is:

```text
/Users/lizhw/Documents/Codex/2026-07-04/new-chat-3/android/app/build/outputs/apk/debug/app-debug.apk
```

After enabling USB debugging on the Xiaomi 13:

```sh
adb install -r /Users/lizhw/Documents/Codex/2026-07-04/new-chat-3/android/app/build/outputs/apk/debug/app-debug.apk
```

On MIUI, allow Vibe Pocket unrestricted battery use if you expect to keep it
available while docked. The current version keeps the event connection active
only while the app is in the foreground.

## Security Boundary

- The Bridge binds to `127.0.0.1`; Tailscale Serve adds tailnet-only HTTPS.
- The Android token is encrypted by an Android Keystore AES-GCM key.
- The phone sends a fixed action vocabulary, not arbitrary terminal bytes,
  shell commands, prompts, or text.
- The helper requires the ChatGPT process and its `Codex` web view. It
  activates ChatGPT explicitly and verifies it is frontmost before pressing a
  desktop Codex control.
- Approval and rejection are direct presses of currently visible Codex buttons,
  so the app does not reinterpret Return or Escape as an approval decision.
- The app does not enumerate or expose historical task content.

## Verification

The Bridge test suite covers desktop action mapping, idempotency, and rejection
of imaginary sessions. A local macOS smoke test verifies that the helper can
locate the currently visible ChatGPT Codex task before any input is sent. The
Android debug APK builds successfully.
