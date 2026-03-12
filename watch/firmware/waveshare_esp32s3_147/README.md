# Waveshare ESP32-S3 1.47 firmware

This folder is intentionally separated from `sonya_watch` to avoid hardware config conflicts.

Planned files for this board:

- `main/` - app entry and board-specific init
- `components/` - drivers/adapters for this board
- `sdkconfig.defaults` - board-specific SDK defaults

Rule: no fallback to configs from other boards. If a feature is unsupported here, return explicit error.
