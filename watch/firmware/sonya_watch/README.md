# Sonya Watch Firmware v0

Минимальный ESP-IDF проект для Waveshare ESP32-S3 Touch AMOLED 2.06.

**Pipeline:** always listen → wake detected → record fixed duration → BLE send to phone

Экран и UI не используются.

## Структура проекта

```
sonya_watch/
├── main/
│   ├── app_main.c      # Инициализация NVS, BLE, audio, wake, pipeline
│   └── Kconfig         # REC_SECONDS, AUDIO_SR, CHUNK_SIZE, WAKE_MODE, etc.
├── components/
│   ├── sonya_ble/      # BLE GATT server, RX/TX, reconnect
│   ├── audio_cap/      # I2S mic → ring buffer → record segment
│   ├── wake/           # Wake engine stub (CMD/BUTTON/RMS)
│   └── protocol/       # Бинарный протокол поверх BLE
├── sdkconfig.defaults
└── README.md
```

## BLE

- Устройство: **SONYA-WATCH**
- Service: SONYA (128-bit UUID)
- RX: Write/WriteNoRsp — команды от телефона (например `START`)
- TX: Notify — события и аудио на телефон

## Протокол

Фрейм: `[type:uint8][seq:uint16][len:uint16][payload]`

| Type | Имя          | Описание              |
|------|--------------|------------------------|
| 0x01 | EVT_WAKE     | Wake detected         |
| 0x02 | EVT_REC_START| Запись началась       |
| 0x03 | EVT_REC_END  | Запись завершена      |
| 0x10 | AUDIO_CHUNK  | Чанк аудио (payload)  |
| 0x11 | EVT_ERROR    | Ошибка (ASCII)        |

## Конфигурация (menuconfig)

```bash
idf.py menuconfig
```

- **Sonya Watch Configuration**:
  - REC_SECONDS — длительность записи (по умолчанию 4 сек)
  - AUDIO_SR — частота дискретизации (16000)
  - CHUNK_SIZE — размер payload для AUDIO_CHUNK (180)
  - WAKE_MODE — CMD / BUTTON / RMS
  - WAKE_BUTTON_GPIO — GPIO кнопки (0 = BOOT)
  - RMS_THRESHOLD — порог энергии для RMS
  - DEVICE_NAME — имя BLE
  - I2S_* — пины I2S (Waveshare: 41, 45, 42, 16)

## Сборка и прошивка

Убедитесь, что ESP-IDF установлен и окружение активировано (например, `export.ps1` или `export.bat` в папке ESP-IDF).

```bash
# Установить target (один раз)
idf.py set-target esp32s3

# Сборка
idf.py build

# Прошивка (подключить плату по USB)
idf.py -p COM3 flash

# Монитор
idf.py -p COM3 monitor
```

Замените `COM3` на ваш порт (Windows: Device Manager → COM-порты).

## Тестовый режим v0 (управление с телефона)

Запись управляется ASCII-командами в RX. TX отправляет бинарные фреймы.

### Команды RX (ASCII, отправлять как UTF-8 строку)

| Команда      | Действие / ответ                                                      |
|--------------|------------------------------------------------------------------------|
| `PING`       | Ответ: EVT_ERROR с текстом `PONG`                                    |
| `REC`        | EVT_WAKE → EVT_REC_START → запись REC_SECONDS → AUDIO_CHUNK… → EVT_REC_END |
| `SETREC:<n>` | Меняет REC_SECONDS (n = 1..10), ответ: EVT_ERROR `REC_SEC=<n>`       |

### Проверка через nRF Connect (Android)

1. Установить **nRF Connect for Mobile** из Google Play
2. Включить Bluetooth, в Scanner найти **SONYA-WATCH**, нажать **Connect**
3. Раскрыть сервис с UUID `12345678-1234-5678-1234-56789abcdef0`
4. На характеристике **TX** (UUID `...7a...`) нажать стрелку вниз (↓) — **Enable Notifications**
5. На характеристике **RX** (UUID `...79...`) нажать стрелку вверх (↑) — **Write**
6. В поле ввода выбрать **Text (UTF-8)** и написать:
   - `PING` → в TX придёт фрейм EVT_ERROR (0x11) с payload `PONG`
   - `SETREC:2` → в TX: EVT_ERROR с payload `REC_SEC=2`
   - `REC` → в TX появятся: EVT_WAKE (0x01), EVT_REC_START (0x02), серия AUDIO_CHUNK (0x10), EVT_REC_END (0x03)
7. На ПК в `idf.py monitor` будут логи:
   ```
   I (xxxx) main: RX: REC (rec_seconds=2)
   I (xxxx) main: REC_START (rec_seconds=2)
   I (xxxx) main: REC_END bytes=64000
   I (xxxx) main: SENT chunks=356 rc=0
   ```

### «Первый успех» — чеклист

- [ ] Подключился с телефона (nRF Connect)
- [ ] Подписался на TX (Notifications)
- [ ] Отправил `REC` в RX
- [ ] В логах на ПК: `REC_START`, `REC_END bytes=...`, `SENT chunks=...`
- [ ] В TX на телефоне видны байты (AUDIO_CHUNK фреймы)

## PC BLE tester (без телефона)

Если на ПК есть Bluetooth-адаптер, можно тестировать RX/TX без скриншотов — скрипт подключается к `SONYA-WATCH`,
подписывается на TX (notify), пишет команды в RX и печатает разобранные фреймы.

Файлы: `tools/ble_client/ble_client.py`, зависимости: `tools/ble_client/requirements.txt`

### Запуск (Windows)

```powershell
cd E:\project\CURSOR\sonya_watch\tools\ble_client
py -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt

# интерактивно (вводи PING/SETREC:2/REC)
.\.venv\Scripts\python .\ble_client.py

# или one-shot
.\.venv\Scripts\python .\ble_client.py --cmd PING --cmd SETREC:2 --cmd REC --no-interactive --keepalive 12

# сохранить сырое аудио (16kHz s16le mono) в файл
.\.venv\Scripts\python .\ble_client.py --cmd REC --audio-out rec.pcm --no-interactive --keepalive 12
```

## Wake v0

- **CMD**: отправка `START` в RX с телефона
- **BUTTON**: нажатие BOOT (GPIO 0)
- **RMS**: stub, не реализован

TODO: заменить на Porcupine/TFLM для keyword spotting.

## Аудио

- 16 kHz, mono, 16-bit PCM
- Ring buffer ~2 сек
- Запись REC_SECONDS в RAM, отправка чанками по BLE

TODO: Waveshare использует ES7210 codec — может потребоваться I2C-инициализация для корректного звука.
