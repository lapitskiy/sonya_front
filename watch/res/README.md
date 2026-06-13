# watch/res — референс стоковой прошивки Waveshare

Сохранено **2026-06-12** с подключённых часов до перепрошивки sonya_watch.

## Устройство (снимок)

| Параметр | Значение |
|---|---|
| MAC | `3c:dc:75:6f:c9:7c` |
| Чип | ESP32-S3 rev v0.2, PSRAM 8MB |
| Flash | 32MB |
| Сток-прошивка | `phone_s3_box_3` v0.4.2-92-g5c6be6c-dirty |
| IDF в прошивке | v5.5.1-dirty (compile Nov 4 2025) |

Boot log: `device_snapshot/boot_log.txt`

## Что здесь лежит

### 1. `ESP32-S3-Touch-AMOLED-2.06/` (521M)
Официальный репозиторий Waveshare: https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-2.06  
commit: `4473d407de97dfb9b752db184767699b5291cc3b`

**Главное для экрана:**
- `examples/ESP-IDF-v5.4.2/03_esp-brookesia/` — стоковое демо `phone_s3_box_3`
- `examples/ESP-IDF-v5.4.2/02_lvgl_demo_v9/` — простой LVGL bring-up
- `examples/ESP-IDF-v5.4.2/01_AXP2101/` — PMU
- `Schematic/` — схема платы

### 2. `Waveshare-ESP32-components/` (6.5M)
BSP и драйверы: https://github.com/waveshareteam/Waveshare-ESP32-components  
commit: `699eabd48896c376e7478abf6ac5714e705f4413`

**Главное для экрана:**
- `bsp/esp32_s3_touch_amoled_2_06/` — пины, init SH8601, brightness, touch
- `display/` — общие display-хелперы

### 3. `device_snapshot/`
- `boot_log.txt` — serial при загрузке
- `stock_factory_9mb.bin` — дамп factory-раздела (offset 0x100000, 9MB)

## Ключевые файлы экрана (быстрый доступ)

```
Waveshare-ESP32-components/bsp/esp32_s3_touch_amoled_2_06/esp32_s3_touch_amoled_2_06.c
Waveshare-ESP32-components/bsp/esp32_s3_touch_amoled_2_06/include/bsp/esp32_s3_touch_amoled_2_06.h
ESP32-S3-Touch-AMOLED-2.06/examples/ESP-IDF-v5.4.2/03_esp-brookesia/main/main.cpp
```

## Пины дисплея (из BSP)

- QSPI: CS=12, PCLK=11, D0-3=4-7, RST=8
- Touch: RST=9, INT=38
- I2C: SDA=15, SCL=14
- 410×502, SH8601, gap X=0x16
- Backlight: нет GPIO, cmd 0x51 через panel IO

## Восстановление стока (если понадобится)

```bash
# factory partition @ 0x100000
esptool.py --port /dev/ttyACM0 write_flash 0x100000 device_snapshot/stock_factory_9mb.bin
```

Полный образ из GitHub (другая прошивка xiaozhi):
`ESP32-S3-Touch-AMOLED-2.06/FirmWare/ESP32-S3-Touch-AMOLED-2.06-xiaozhi-251104.bin`
