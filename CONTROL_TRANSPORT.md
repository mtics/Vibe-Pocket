# Vibe Pocket control transport

Vibe Pocket keeps two control paths because they solve different problems.

- `bridge`: owns task discovery, exact task selection, target-bound model and reasoning settings, workflows, and other task-aware desktop semantics.
- `virtual-hardware`: owns physical keyboard reports and latency-sensitive commands against the frontmost Codex task.
- `micro`: an isolated debug experiment that attempts to present the Xiaomi 13 as a BLE vendor HID peripheral using the observed Codex Micro transport.

The `micro` experiment does not replace or fall back to `bridge`. Once a BLE event is submitted, an uncertain result is quarantined by disconnecting instead of replaying it through another path.

## Bluetooth ownership

Android exposes the phone's Classic HID keyboard and BLE HOGP peripheral through the same physical adapter. They must never be active together. The application-scoped hardware owner therefore uses this handover:

1. Pause new Classic HID reports.
2. Drain the active report, cancel queued reports, and serialize a final all-zero report.
3. Disconnect Classic hosts, end the HID registration, and invalidate pending profile acquisition.
4. Open and advertise the Micro GATT server only after the release is acknowledged.
5. Stop advertising, cancel every BLE connection, and wait for each `DISCONNECTED` callback.
6. Close the GATT server and terminate the owning process because Android exposes no server-close acknowledgement.
7. Ask a separate recovery process to post the notification and wait for its acknowledgement before terminating the GATT-owning process.
8. Route the notification tap through a recovery-process trampoline that waits for the old PID to disappear before launching the foreground activity that registers Classic HID.

A failed handover stays closed instead of exposing both identities. Classic HID restoration waits for its release callback. A timed-out BLE disconnect barrier is abandoned only to close the GATT owner and cross the existing recovery-process boundary; Classic HID is never restored in that process.
Once a GATT server has opened, Classic restoration always crosses a process boundary. Binder death is the observable cleanup boundary for the old server; the fresh process cannot inherit that server handle.
Android automatically unregisters a `BluetoothHidDevice` application when it is not foreground, and background activity-launch rules prevent an alarm from silently foregrounding Vibe Pocket. The experimental Micro stop flow therefore remains safely unregistered until the user taps the recovery notification or opens Vibe Pocket.
Micro refuses to claim the adapter when notifications are disabled, so a session cannot begin without a visible recovery path.
Any `openGattServer` attempt requires the same process boundary even if Android returns no server handle. If notification permission or the recovery channel changes while Micro is active, the service retains ownership and retries recovery instead of restoring Classic in-process.

## Evidence boundary

The protocol core is covered by JVM tests. Compatibility is not established until a Xiaomi 13 and macOS trace shows all of the following:

1. The three GATT services are installed and advertised.
2. macOS initiates pairing and completes system-mediated bonding.
3. The host negotiates an ATT MTU of at least 66 and subscribes to the input report.
4. ChatGPT Desktop sends at least two recognized RPC requests and receives a complete response sequence.
5. A debug `ACT06` press and release causes an observed desktop action.

Android's public GATT server API supports an Included Service relationship. The experiment currently keeps the standard Battery Service independently discoverable because the available implementations disagree and no authoritative physical capture establishes that the target host requires the optional relationship. This evidence gap must be resolved during physical testing before changing the schema.

A macOS pairing created for the Classic HID identity may retain its original VID, PID, and HID compatibility state when the same phone starts advertising the Micro service. The host must enumerate `303A:8360` after a fresh pairing before protocol compatibility can be claimed. A GATT connection that still reports the old Classic identity is cache evidence, not a successful Micro session.

The observed vendor protocol is undocumented and version-sensitive. Identity values are emitted only for compatibility research and do not imply an official OpenAI or Work Louder device.

The observed pairing path provides encrypted HOGP attributes but no verified passkey or numeric-comparison authentication. Android's GATT peer identity is not cryptographically bound to the Bridge device credential, so the experiment must not claim end-to-end host authentication. A controlled, close-range radio environment is a testing precondition, not a security control.

The HID report body is 63 bytes. Notification and direct Write Request values have `MTU - 3` bytes available, so a 63-byte value requires MTU 66 and a 64-byte Output value containing Report ID 6 requires MTU 67. Prepare Write fragments have `MTU - 5` bytes available and may assemble that 64-byte form across writes. Android passes incoming write bytes to `onCharacteristicWriteRequest`; the characteristic's initial 63-byte cached value is not a remote-write capacity declaration.

Micro has no persistent host allowlist or revocation claim. Those controls require a stable peer identity plus explicit enrollment and recovery UX, neither of which is established by the current protocol evidence. The research service instead starts only by explicit user action, owns one active host at a time, fails closed on uncertainty, and is excluded from production artifacts.

The evidence, competing implementations, observed wire values, and unresolved gaps are recorded in [CODEX_MICRO_RESEARCH.md](CODEX_MICRO_RESEARCH.md).

Selected production target: `bridge` plus `virtual-hardware`

Experimental target: `micro`
