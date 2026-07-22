# Codex Micro protocol research

Status: interoperability research, not an official specification

Research snapshot: 2026-07-23

## Decision

Vibe Pocket keeps Codex Micro support inside the private `researchDebug`
variant. The normal controller remains an authenticated Bridge plus standard
Bluetooth HID.

That boundary follows from the capability of each path:

- Micro reports physical intents and receives device-facing status or lighting
  requests. It does not expose task identity, the model catalog, reasoning
  choices, or a task-bound action outcome.
- The Bridge discovers exact Codex tasks, binds mutations to a `TargetRef`, and
  observes post-action state.
- A BLE acknowledgement proves transport completion only. It cannot prove that
  Codex applied the intended business action.

There is no fallback from an uncertain Micro event to Bridge or HID because a
retry on another transport could execute one touch twice.

## Evidence model

Every claim below belongs to one of these classes:

1. **Retail-probed**: application-level behavior observed while a public tool
   communicated with retail Codex Micro hardware. This is stronger than a
   compatibility implementation, but it is not a raw USB or Bluetooth capture.
2. **Bundle-observed**: behavior or identifiers recovered from a named ChatGPT
   Desktop or Work Louder bundle.
3. **Host-emulation observed**: a synthetic HID or `node-hid` shim was accepted
   by ChatGPT Desktop and exercised end to end.
4. **Compatibility-device observed**: a third-party physical device was reported
   to work with ChatGPT Desktop.
5. **Implementation-inferred**: behavior appears in source but has no committed
   capture or result artifact.
6. **Locally verified**: Vibe Pocket tests or a Xiaomi 13 and Mac trace establish
   the behavior in this workspace.

No inspected public project contains retail firmware, a retail descriptor dump,
a complete retail USB trace, or a Bluetooth air capture. `microd` provides the
strongest retail-device probing found, while `decode-codex`, AgentController,
and the Stream Deck emulator provide the strongest host-side evidence. Claims
outside those boundaries remain compatibility reconstruction.

## Source corpus

The research pinned immutable revisions rather than relying on moving default
branches.

### Retail probe and host bundle

| Project | Revision | Evidence and limit |
| --- | --- | --- |
| [PlaneshiftDev/microd](https://github.com/PlaneshiftDev/microd) | `7365e4578a3292ae6952c8bfc1621275703a7d9c` | Probes retail firmware `0.4.1`; observes `303A:8360`, Report ID 6, compact notifications, integer lighting fields, effects, and paced report chunks. It does not publish a raw descriptor or transport capture. |
| [JimLiu/decode-codex](https://github.com/JimLiu/decode-codex) | `6fd43d66ccad32c9c1ab83b9704e0bbbbf2d4c7b` | Extracts a local `ChatGPT.app/app.asar` and reconstructs `Project2077`, discovery, communication, OAI RPC, six agent slots, lighting, battery, HID, radial input, reconnect, and refresh behavior. Restored names are interpretive and the Work Louder dependency still owns lower-level framing. The repository does not license extracted code for reuse. |
| [OpenAgentsInc/openagents](https://github.com/OpenAgentsInc/openagents) | `ad9e9e360a5fd4d73f7c8d7399233959b7cc4a83` | Independent desktop-package teardown confirms Project2077, `AG00` through `AG05`, radial input, battery, and RGB or ambient lighting. It has no byte-level transport evidence. |

### Host emulation and adapters

| Project | Revision | Evidence and limit |
| --- | --- | --- |
| [gantrol/AgentController](https://github.com/gantrol/AgentController) | `0385949154ed4cc28214980512fc5d4aadae0f57` | Windows VHF synthetic `303A:8360`, host bundle analysis, live status and lighting traffic. Its command reference is useful but its 64 KiB message limit is an implementation policy, not a protocol fact. PolyForm Noncommercial. |
| [mpociot/codex-micro-stream-deck-emulator](https://github.com/mpociot/codex-micro-stream-deck-emulator) | `7093bd48f0bcb953f623b40c727470e545b48df3` | Work Louder source-map and ChatGPT bundle analysis plus a `node-hid` shim accepted for keys, dial, and lighting. This proves host behavior above the physical transport, not BLE. |
| [maxxspotter/codex-micro-app](https://github.com/maxxspotter/codex-micro-app) | `cf323ada1e9716073d0748caea503f6e0974ba1c` | Phone UI, task discovery, pairing, and a process-level `node-hid` shim derived from the Stream Deck emulator. Useful product evidence; not a real HID or BLE path. |
| [thesammykins/not-a-codex-micro](https://github.com/thesammykins/not-a-codex-micro) | `1290bfa9c5b108a294f4f42d7dc16c9b6b02885f` | Drives private loaded modules through Chrome DevTools `Runtime.evaluate`. It bypasses USB and BLE. |
| [DevVig/microbridge](https://github.com/DevVig/microbridge) | `750b405804782f26a05ab636ce38d9391dbe2e82` | App-server proxy that creates a process request per action. It supports the Bridge direction but provides no hardware capture. |
| [scf4/codex-midi](https://github.com/scf4/codex-midi) | `a4b76fa16474960d2324b33063578d07a27c621a` | Authenticated socket plus `node-hid` shim; no independent raw protocol provenance. |

### BLE compatibility devices

| Project | Revision | Evidence and limit |
| --- | --- | --- |
| [imliubo/codex-micro-4-core2](https://github.com/imliubo/codex-micro-4-core2) | `2ee23a4ab696f94bb78d250f28cc4a9b879ba079` | MIT BLE HOGP firmware and the strongest dated author report of physical macOS and ChatGPT compatibility. It publishes identity, descriptor, framing, RPC, and event values but no raw capture. |
| [Patchself/CodexMacro](https://github.com/Patchself/CodexMacro) | `79b9795f55e8ba84d3bc31efdef837b261f080cc` | LGPL Android BLE peripheral derived from the Core2 work. Strongest Android lifecycle comparison, not an independent protocol authority. |
| [VoiceFlowTeam/codex-faces-bridge](https://github.com/VoiceFlowTeam/codex-faces-bridge) | `369ed9f556b6bd4bd81f123fdbd0d0c6a09fa2a6` | BLE HOGP, MTU 185, long events, and long or compact host-field parsing. The default branch has no license file and no committed validation trace. |
| [xuruiray/Stopwatch-Micro](https://github.com/xuruiray/Stopwatch-Micro) | `216def7b35b341b04798f33f2a9718c86082c798` | Core2-derived MIT BLE implementation. Records the important compatibility observation that macOS may provide an Output write as 64 bytes including ID 6 even though the HID report body is 63 bytes. |
| [shirok1/xiaomiao-embassy](https://github.com/shirok1/xiaomiao-embassy) | `3a3d092ade5b9bdbac0a99a77642bb4a5b982e3d` | ESP32 BLE compatibility firmware with Device Information, Battery, HID, encrypted attributes, PnP identity, and the same 64-byte incoming-write observation. No license was found at the pinned revision. |
| [moyi7712/CodexJoystick](https://github.com/moyi7712/CodexJoystick) | `d4f5a783dce319d8bbf330ea636d300192b1f6d7` | ESP32 vendor HID compatibility path. It sees device-facing colors and effects but has no task identity or semantic outcome. |

### USB compatibility devices

| Project | Revision | Evidence and limit |
| --- | --- | --- |
| [hcyniubi/doio-kb16-codex-micro](https://github.com/hcyniubi/doio-kb16-codex-micro) | `635bfe709b2ca399da6655bc87bc492ed12f8976` | MIT QMK firmware and Windows mapper. Confirms the Project2077 workflow is not intrinsically BLE-only. |
| [fttawa/codex-micro](https://github.com/fttawa/codex-micro) | `80d20ba1eec66071261505e089e0851cd91761eb` | ESP32-S3 USB HID and Windows virtual HID. The PCB is explicitly unassembled; software is PolyForm Noncommercial and hardware or docs are CC BY-NC-SA. |
| [aaronpearce/codex-pico](https://github.com/aaronpearce/codex-pico) | `0d6e5464e4d832e0cbc4890898b0611e7151ca3b` | MIT Pico USB composite HID with long events and broad RPC acknowledgements. The repository has no committed physical result. |
| [raylax/codex-hid](https://github.com/raylax/codex-hid) | `70ae3d8e2c8981374cf9db2e7709de690bcd2da1` | ATmega32U4 USB compatibility firmware. No license was found at the pinned revision. |

### Product references and exclusions

| Project | Revision | Why it is not wire evidence |
| --- | --- | --- |
| [stephenleo/OpenMicro](https://github.com/stephenleo/OpenMicro) | `73a153dbdbf877505df0fff6dda1f9ec4cd34dfc` | Reproduces the controller workflow through conventional gamepads, lifecycle hooks, deep links, terminal input, shortcuts, and scoped automation. It does not emulate the Micro identity or RPC transport. |
| [conol-ai/openmicrokbd](https://github.com/conol-ai/openmicrokbd) | `2c4176719bab5c546792e5da57dd43db1703d81c` | Uses conventional keyboard, consumer, and updater reports with different VID/PID, usage page, report width, and commands. Excluded from Project2077 protocol evidence. |

## Compatibility surface

### Identity and discovery

| Field | Current compatibility value | Evidence boundary |
| --- | --- | --- |
| Device type | `Project2077` | bundle-observed |
| VID | `0x303A` | retail-probed, bundle, implementations |
| PID | `0x8360` | retail-probed, bundle, implementations |
| Usage page | `0xFF00` | bundle and implementations |
| Usage | `0x01` | implementations |
| Report ID | `6` | retail-probed, bundle, implementations |
| Manufacturer | `Work Louder` | bundle and implementations |
| Services | Device Information, Battery, HID | compatibility implementations |

The advertised name is not a compatibility invariant. Public implementations
use both `Codex Micro` and `kbd-1.0-codex-micro`, while host emulators may expose
no BLE name at all. Vibe Pocket currently leases `Codex Micro` to stay aligned
with its primary Android reference, but successful name mutation is not evidence
of host recognition.

### Report and ATT boundaries

The HID descriptor declares a 63-byte Input body and a 63-byte Output body. A
USB-style or macOS callback view may prepend Report ID 6 and expose 64 bytes.
Vibe Pocket normalizes only these two forms:

```text
BLE body:       [channel, length, 61-byte payload/padding]       = 63
Explicit form:  [report ID 6, channel, length, payload/padding] = 64
```

The Android GATT callback supplies the remote write bytes independently of the
characteristic's locally cached value. The initial 63-byte cached value is
therefore not a maximum-write-length declaration. Vibe Pocket keeps the cached
value and notifications at the descriptor-declared 63 bytes while accepting and
normalizing an incoming 64-byte explicit form.

ATT capacity is directional and operation-specific:

- Notification and direct Write Request values have at most `MTU - 3` bytes.
- A 63-byte notification or direct write therefore requires MTU at least 66.
- A direct 64-byte write including Report ID requires MTU at least 67.
- Prepare Write carries at most `MTU - 5` bytes per fragment and may assemble a
  64-byte value across fragments. That does not remove the MTU 66 requirement
  for device-to-host 63-byte notifications.

### Framing

```text
offset  size  meaning
0       1     channel, observed value 2 for RPC
1       1     payload length, 0..61
2       61    UTF-8 JSON fragment followed by zero padding
```

Device-to-host JSON is newline-terminated before fragmentation. Host-to-device
requests are assembled as a complete JSON object without requiring a newline.
The protocol has no observed sequence number, checksum, total length, or replay
identifier.

Consequences:

- Vibe Pocket serializes notifications and never replays an uncertain event.
- The decoder resets on explicit invalid input, overflow, prepared-write fault,
  disconnect, generation change, unsubscribe, or quarantine.
- It does not guess that a fragment beginning with `{"method"` is a new request;
  that byte sequence is also legal at the start of a nested object.
- The 4096-byte receive limit is a local denial-of-service bound, not a claimed
  retail protocol limit.

### RPC and events

The envelope is JSON-RPC-like but has no observed `jsonrpc: "2.0"` member:

```json
{"method":"device.status","params":null,"id":42}
```

Current compatibility methods are:

| Method | Local behavior | Evidence boundary |
| --- | --- | --- |
| `sys.version` | returns the research implementation version | compatibility implementations |
| `device.status` | version, profile, layer, battery, charging | retail probe and implementations |
| `v.oai.thstatus` | bounded compatibility acknowledgement | bundle and implementations; exact result unverified |
| `v.oai.rgbcfg` | bounded compatibility acknowledgement | retail probe, bundle, implementations; exact result unverified |
| `lights.preview` | bounded compatibility acknowledgement | compatibility implementations |
| `host.focused_app` | bounded compatibility acknowledgement | compatibility implementations |

A generic `{"ok":true}` response proves only that the method was recognized by
the local compatibility layer. It does not prove that lighting or focus state
was applied. Unknown methods return `-32601`; incompatible projects disagree on
whether unknown methods should instead receive a generic success, so this is
not treated as a retail invariant.

Observed or interoperable device events include:

- `v.oai.hid` with `k`, `act`, and optional `ag`
- `v.oai.rad` with normalized `a` and `d`
- Agent keys `AG00` through `AG05`
- Command keys `ACT06`, `ACT07`, `ACT08`, `ACT09`, `ACT10`, and `ACT12`
- Encoder keys `ENC_CC`, `ENC_CW`, and `ENC`

`ENC_CC` is intentionally preserved as a wire value. `ACT10` has stronger
cross-project support than the composite-looking `ACT10_ACT11`; the latter may
be a layout-slot label rather than a retail physical ID.

Retail probing and some host emulators expose compact `m/p` envelopes, while
Core2-derived physical compatibility reports and other implementations use long
`method/params`. The current local emitter retains long fields because it is
already compatibility-tested by public devices. An A/B physical test against a
fingerprinted ChatGPT build is required before changing the wire form.

## Trust boundary

Encrypted GATT permissions and system bonding protect the link but do not prove
an authenticated mapping between the BLE peer and the Bridge device credential.
Android exposes a `BluetoothDevice` identity to the callback, but this project
has no verified protocol that binds that identity to the Mac Bridge public or
secret credential. A cross-layer allowlist would therefore create an
unsubstantiated security claim.

The research peripheral:

- starts only after an explicit user action;
- accepts one active host at a time;
- rejects competing writes and quarantines uncertain notification outcomes;
- does not log task content, model data, or reasoning data;
- must be tested in a controlled radio environment; and
- is absent from standard builds through source-set separation and artifact
  verification.

"Controlled radio environment" is a deployment precondition, not an
authentication control. Persistent host allowlisting, revocation, and automatic
resume remain blocked until a stable peer identity and an explicit enrollment
and recovery UX can be validated. The app must not describe the current path as
end-to-end host authentication.

## Code structure

Directory context and local names share the semantics:

```text
hardware/micro/
  Peripheral.kt   Android callback orchestration only
  Gatt.kt         service and descriptor schema
  HostSession.kt  one-host generation and uncertainty boundary
  Link.kt         observable compatibility phases
  Writes.kt       prepared-write transaction and commit boundary
  Queue.kt        single-flight notifications
  protocol/
    Frame.kt      report normalization, assembly, and encoding
    Rpc.kt        bounded host request dispatch
    Signal.kt     physical wire events
```

The flow is deliberately composed rather than hidden in a product-prefixed
controller:

```text
GATT callback
  -> owner and generation check
  -> direct or prepared write commit
  -> report normalization
  -> bounded frame assembly
  -> RPC dispatch
  -> serialized response notification
  -> link validation
  -> physical signal eligibility
```

Micro never imports the Bridge catalog or session state. The Bridge remains the
semantic authority, and the standard APK is checked for known Micro classes,
manifest entries, protocol identifiers, and research notices.

## Physical validation gate

Compatibility on Xiaomi 13 remains unproven until a clean-pairing trace records:

1. Exact ChatGPT Desktop build, macOS version, phone build, and research APK.
2. Installed services, descriptor bytes, PnP identity, bond state, and the host
   identity shown by Android.
3. Negotiated MTU plus every Output callback's size, leading byte, direct or
   prepared form, offsets, and response status.
4. Subscription to Report ID 6 Input and successful delivery of 63-byte
   notifications.
5. At least two recognized host requests and complete response notifications.
6. A controlled long-versus-compact envelope A/B test with visible or readable
   host behavior.
7. `ACT06` press and release producing one observed ChatGPT action, never two.
8. Disconnect, unsubscribe, quarantine, and stop clearing state without replay.
9. GATT shutdown completing before Classic HID restoration across the existing
   process boundary.

An old Classic HID bond can preserve stale macOS identity and descriptor cache.
Advertising, connection, compilation, or a GATT subscription alone does not
establish Codex Micro compatibility.

## Open questions

- Whether retail BLE firmware exists and, if so, which descriptor and services
  it uses.
- Whether Battery must be included by HID or only independently discoverable.
- Whether every supported host accepts both long and compact envelopes.
- Exact response bodies for lighting and focus methods.
- Whether physical PTT is always `ACT10` and which build defines encoder
  orientation.
- Which stable BLE identity, if any, can support user-visible host enrollment
  and revocation on stock Android.

These questions require a build-fingerprinted physical trace or a new bundle
comparison. They must not be resolved from project names or field names alone.
