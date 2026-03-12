# Watch firmware layout

Separate firmware by hardware board:

- `sonya_watch/` - current firmware project
- `waveshare_esp32s3_147/` - separate project for Waveshare ESP32-S3 1.47"

Board-specific configs must stay isolated:

- `sdkconfig`, `sdkconfig.defaults`
- pin mapping and BSP files
- display/touch/PMU initialization
