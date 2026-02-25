# sonya_front

## Wake phrase (offline): Vosk keyword spotting

This app now uses **Vosk** offline ASR with a **narrow grammar** to detect the wake phrase:

- `соня приём` (also accepts `соня прием`)

When the wake phrase is detected, the app switches into the existing `SpeechRecognizer` flow to capture the command.

### 1) Download a Russian Vosk model

Download a Russian model folder named like:

- `vosk-model-small-ru-0.22`

From the official Vosk models page: `https://alphacephei.com/vosk/models`

### 2) Put the model into Android assets

Unzip/copy the whole model directory into:

- `app/src/main/assets/vosk-model-small-ru-0.22/`

Inside it, you should see typical Vosk files/folders like `am/`, `conf/`, `graph/`, etc.

### 3) Build & run

The first start will **copy the model from assets to internal storage** (Vosk needs a real filesystem path).

### 4) Changing the wake phrase / model folder

Edit `VoskWakeWordEngine` defaults:

- `modelAssetDir` (model folder name in assets)
- `grammarPhrases` (phrases list)

