# MotoTalk

Android-интерком для мотоциклистов: телефон — аудио-хаб, шлемы подключены каждый к своему телефону, телефоны связаны напрямую без Интернета.

- План проекта: [MotoTalk_High_Level_Project_Plan.md](MotoTalk_High_Level_Project_Plan.md)
- Требования к POC: [MotoTalk_POC_Requirements.md](MotoTalk_POC_Requirements.md)
- Тест-кейсы M1: [MotoTalk_M1_Test_Cases.md](MotoTalk_M1_Test_Cases.md)
- APK: [Releases](https://github.com/aster-niene/mototalk/releases)

## Статус

**M0 — каркас** (§11 требований):

- `RideService` — foreground service типов `microphone|connectedDevice`, уведомление с кнопкой Stop;
- runtime-разрешения и проверки радио перед стартом;
- журнал диагностики JSONL с метками и экспортом;
- наблюдатели за audio mode, communication device, аудиоустройствами, чужими плеерами, HFP/SCO, A2DP, Bluetooth, Wi-Fi и экраном.

**M1 — звук через шлем, один телефон:**

- `AudioSession` — маршрут через SCO гарнитуры (FR-1): открытие, удержание режима, потеря и восстановление, звонки (FR-11);
- `VoiceIo` — AudioRecord/AudioTrack 16 kHz, Loopback;
- **Record 10 s** (WAV) и **Duck test** (FR-2).

Nearby (M2) ещё не реализован.

## Сборка

Нужны JDK 17–23 (проще всего JDK 21 или JBR из Android Studio; Gradle 8.12 не запускается на JDK 24+) и Android SDK (`ANDROID_HOME`). Платформу API 36 Gradle докачает сам.

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Установка на телефон с включённой отладкой по USB:

```bash
./gradlew installDebug
```

### Почему такие версии

AGP 8.10.1 — максимум, который открывает установленная Android Studio 2024.3.2 (Meerkat Feature Drop); Kotlin 2.1.21 — под ту же Studio; Gradle 8.12 — уже был в кэше (AGP 8.10 требует ≥ 8.11.1). Nearby закреплён на 19.3.0: версии 19.4+ собраны Kotlin 2.3/2.4 и не компилируются Kotlin 2.1. После обновления Android Studio поднимаем одним шагом: AGP 9.x, Kotlin 2.4, Nearby 19.5.1, свежие AndroidX.

## Как пользоваться

1. **Grant permissions** — один раз.
2. **Loopback** — открывает голосовой канал (SCO) к шлему, и ты слышишь себя. **START RIDE** — тот же канал без прослушивания себя (в M2 сюда добавится связь с другим телефоном). В шторке появляется уведомление с кнопкой Stop.
3. **Record 10 s** — записать 10 с с микрофона в WAV. **Duck test** — на 5 с запросить приглушение музыки.
4. **▶** — отметить в журнале начало следующего тест-кейса (одно касание).
5. **Stop** — в приложении или в уведомлении.
6. **Export** — отправить журналы и записи (Share).

Подробно по шагам: [MotoTalk_M1_Test_Cases.md](MotoTalk_M1_Test_Cases.md).

Карточка **Check** предупреждает, если выключен Bluetooth или Wi-Fi или телефон подключён к Wi-Fi-сети (для P2P-тестов нужно отключиться, §8.7).

## Журнал диагностики

`filesDir/logs/mototalk-<дата>-<модель>-<id>.jsonl`, одна строка — одно событие:

```json
{"wallMs":1791280000000,"monoMs":123456,"phone":"SM-S918B-3fa2","event":"audio_mode","mode":"IN_COMMUNICATION"}
```

`wallMs` — для сведения журналов двух телефонов, `monoMs` — для интервалов внутри одного телефона.

## Структура

```text
app/src/main/java/dev/mototalk/
├── audio/       AudioSession (маршрут SCO, звонки, duck test), VoiceIo (запись/воспроизведение),
│                AudioObserver, Dsp, Wav, RouteRules, AudioStats, Recordings
├── bluetooth/   RadioObserver — HFP/SCO, A2DP, адаптер, Wi-Fi
├── diag/        DiagnosticsLog (JSONL), Json, DeviceInfo/DeviceIdentity, Preflight, Names, ScreenObserver
├── intercom/    SessionState — три оси состояния (FR-9)
├── service/     RideService (FGS), SessionStore, RideNotification
└── ui/          MainActivity, MainScreen (Compose), Permissions, Theme
```
