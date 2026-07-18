# Vibe Pocket control transport

The selected implementation is split into a strict hardware target and a compatible control path.

- `direct-task`: supported Codex app-server and hooks; controls tasks, not Codex window commands.
- `virtual-hardware`: selected. Xiaomi 13 exposes Android HID Device and BLE GATT capabilities.
- `strict-project2077`: blocked by the official USB-only VID/PID/usage-page discovery and private RPC protocol.
- `compatibility`: generic Bluetooth HID for standard keys plus macOS Accessibility for Codex window commands.

Selected target: `virtual-hardware`
Compatibility path: `macOS Accessibility`
