# Sonya Companion (Android) — Stage 1: audio pipeline

Цель этапа 1: проверить полный голосовой pipeline **до UI**:

- BLE: подключение к `SONYA-WATCH`
- подписка на TX (notify)
- приём `AUDIO_CHUNK` (PCM s16le 16kHz mono)
- склейка в WAV
- POST WAV на backend (URL настраиваемый)

## Важно

В репозитории лежит **код/файлы для вставки в Android Studio**, без `gradle-wrapper.jar` (бинарники не коммитим).
Правильный путь: создать новый проект в Android Studio и заменить/добавить файлы из `android/sonya_companion/app/src/...`.

## 1) Создать проект

Android Studio → New Project → **Empty Views Activity** (Kotlin)  
Package name: `com.sonya.companion` (или поменяй в файлах)
Min SDK: 26+

## 2) Разрешения

Замени `app/src/main/AndroidManifest.xml` на файл из этой папки:
`android/sonya_companion/app/src/main/AndroidManifest.xml`

Он уже содержит:
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION` (иногда нужен для скана на старых Android)
- `INTERNET` (для upload)

## 3) Зависимости

Используем только Android SDK (без сторонних библиотек).

Нужен `androidx.appcompat:appcompat` (в Empty Views Activity он есть по умолчанию).

## 4) Файлы

Скопируй в свой проект:

- `app/src/main/java/com/sonya/companion/`:
  - `MainActivity.kt`
  - `BleClient.kt`
  - `BleUuids.kt`
  - `Protocol.kt`
  - `WavWriter.kt`
  - `BackendUploader.kt`
- `app/src/main/res/layout/activity_main.xml`

## 4) Запуск

1. Включи Bluetooth на телефоне
2. Запусти приложение
3. Нажми Scan → выбери `SONYA-WATCH` → Connect
4. Нажми `REC` (или `SETREC:2`, потом `REC`)
5. После `REC_END` появится путь к WAV и кнопка Upload

## Backend

По умолчанию в UI стоит `http://10.0.2.2:8000/upload` (это localhost хоста из Android Emulator).
Для реального телефона укажи реальный URL твоего backend (например `https://.../upload`).

Отправка делается как **raw body** с `Content-Type: audio/wav`.

## Logcat (без скринов)

Если телефон подключён по USB в режиме отладки, все логи приложения дублируются в logcat с тегом `SonyaCompanion`.

На ПК:

```powershell
adb devices
adb logcat -s SonyaCompanion:D
```

Если `adb devices` пусто:
- включи **USB debugging** (Developer options)
- подключи кабель, выбери режим **File transfer (MTP)** или **USB tethering** (иногда помогает)
- на телефоне нажми **Allow USB debugging** для этого ПК

