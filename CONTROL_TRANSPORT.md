# Vibe Pocket control transport

The selected implementation is split into a strict hardware target and a compatible control path.

- `direct-task`: supported Codex app-server and hooks; discovers task and model IDs without claiming control of the desktop process.
- `virtual-hardware`: selected. Xiaomi 13 exposes Android HID Device and BLE GATT capabilities.
- `strict-project2077`: blocked by the official USB-only VID/PID/usage-page discovery and private RPC protocol.
- `compatibility`: generic Bluetooth HID for standard keys plus scoped macOS Accessibility for the remaining Codex window commands.

Selected target: `virtual-hardware`
Compatibility path: `macOS Accessibility`
