# Voice Wake Flow

Этот файл описывает текущую логику голосовой активации в `app/src/main/java/com/example/sonya_front/VoiceRecognitionService.kt`, чтобы при следующих правках не ломать переходы между `wake`, `verify`, `TTS` и `command mode`.

## Основной сценарий

1. Запущен `VoskWakeWordEngine`.
2. При wake-trigger вызывается `startWakeVerificationThenCommand()`.
3. Сервис временно переключает `SpeechRecognizer` на `verifyListener`.
4. Если короткая проверка подтверждает wake-фразу, вызывается `speakWakeThenEnterCommandMode()`.
5. Сервис произносит короткий TTS-ответ (`да`, `слушаю`, и т.д.).
6. После `onDone()` или fallback timeout вызывается `enterCommandMode()`.
7. В `enterCommandMode()` сервис обязан вернуть основной `commandRecognitionListener`.
8. В command mode обрабатываются:
   - обычный текст команды
   - `отбой` / `стоп` -> отмена
   - `конец связи` / `спасибо` -> завершение и отправка

## Важные методы

- `startWakeWordDelayed()` — запускает wake engine и сбрасывает command mode.
- `startWakeVerificationThenCommand()` — запускает короткую проверку wake-фразы.
- `beginWakeTransitionOnce()` — защита от повторного запуска одного и того же wake-перехода.
- `speakWakeThenEnterCommandMode()` — TTS-ответ после wake.
- `enterCommandMode()` — включает непрерывное распознавание команды.
- `finalizeNow()` — завершает command mode, останавливает `SpeechRecognizer`, отправляет текст и возвращает wake mode.

## Критичные инварианты

### 1. Один wake-cycle -> один переход в TTS/command

Во время одного wake-события `speakWakeThenEnterCommandMode()` должен запускаться только один раз.

Если убрать эту защиту, `onPartialResults()`, `onResults()`, `onError()` и timeout verify-фазы могут по несколько раз дергать один и тот же переход. Симптом: одна и та же ответная фраза TTS повторяется много раз.

Для этого используется `wakeTransitionStarted` + `beginWakeTransitionOnce()`.

### 2. После verify нужно вернуть основной listener

Во время verify-фазы сервис временно ставит `verifyListener`.

В `enterCommandMode()` обязательно нужно вернуть `commandRecognitionListener`.

Если этого не сделать, командный режим визуально включится, но `спасибо`, `конец связи`, `отбой`, `стоп` не будут обрабатываться корректно, потому что активным останется listener для wake-проверки.

### 3. При finalize нужно сразу выключать continuous listening

В `finalizeNow()` сначала нужно:

- выставить `isContinuousListening = false`
- остановить/уничтожить `SpeechRecognizer`
- выключить `broadcastCommandModeState(false)`

И только потом заниматься дальнейшей обработкой результата.

Если сделать наоборот, возможны лишние callbacks, зависание режима прослушки и наложение TTS/распознавания.

## Finish phrases

Сейчас стоп-фразы завершения команды:

- `конец связи`
- `спасибо`

Они ищутся в:

- `onPartialResults()`
- `onResults()`

Если phrase найдена:

1. берется текст до phrase
2. сохраняется в `combinedTextBuilder`
3. вызывается `finalizeNow()`

Важно: все, что сказано после `спасибо`, в текущей логике в команду не входит.

## Echo protection

После перехода в command mode сервис краткое время игнорирует собственную wake-response фразу через:

- `ignoreWakeResponseEchoUntilMs`
- `shouldIgnoreWakeResponseEcho()`

Эта защита нужна, чтобы TTS-ответ (`да`, `слушаю`) не попадал в текст команды.

## Если снова появится баг

Сначала проверить:

1. Какой listener реально установлен после `enterCommandMode()`.
2. Сбрасывается ли `wakeTransitionStarted` в начале нового wake-cycle.
3. Вызывается ли `finalizeNow()` при `спасибо`.
4. Не приходит ли `спасибо` в измененном виде от распознавалки.
5. Не остается ли `SpeechRecognizer` активным после `finalizeNow()`.

## Что не менять без необходимости

- Не убирать `beginWakeTransitionOnce()`.
- Не ставить `verifyListener` как постоянный listener.
- Не переносить отключение `isContinuousListening` в конец `finalizeNow()`.
- Не запускать wake engine параллельно с активным command mode.

