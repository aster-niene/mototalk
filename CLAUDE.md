# MotoTalk — заметки для Claude

Мотоциклетный интерком без Интернета: шлем ⇄ телефон ⇄ Nearby P2P ⇄ телефон ⇄ шлем. Владелец пишет по-русски, хочет быстрый POC. Публичный репозиторий: github.com/aster-niene/mototalk, APK выкладываются в Releases.

## Где что

- `MotoTalk_POC_Requirements.md` — требования и принятые решения (D1–D15). Решения не пересматривать без владельца.
- `MotoTalk_Helmet_Unit.md` — **текущее направление**: свой блок в шлем на ESP32. Железо, распиновка гарнитуры EJEAS, схема подключения, этапы H0–H3, что заказано.
- `MotoTalk_Intercom_Test_Cases.md`, `MotoTalk_M1_Test_Cases.md` — тест-кейсы простым языком.
- `MotoTalk_LE_Audio_Research.md` — почему не LE Audio.
- `app/` — Android-приложение (Kotlin, Compose), пакет `dev.mototalk`.
- `firmware/h0_radio_test/` — прошивка ESP32 (Arduino).
- `testdata/` — логи и записи с тестов (в git не попадают).

## Где мы сейчас (2026-10-17)

- Приложение 0.2.0: интерком двух телефонов (0.1.0) + экран «Проверка радио шлема (H0)».
- Шлемы EJEAS через Bluetooth Classic не дают хорошую музыку и микрофон одновременно → делаем свой блок на ESP32 (D15).
- H0 частично пройден: плата принимает музыку по A2DP без перебоев; звук в шлеме без модулей плохой (8-битный ЦАП, мост между GPIO25/26).
- Модули заказаны (PCM5102A, MAX97220, MAX4466, TP4056, HT7833), приедут ~24 окт – 2 нояб. Дальше — H1: прошивка под итоговую схему из `MotoTalk_Helmet_Unit.md`.

## Правила владельца

- В шлеме никаких кнопок — гарнитура это только динамики и микрофон, всё автоматически.
- Микрофон телефона не использовать (телефон в кармане, ветер).
- Тесты — без набора текста, инструкции простым языком.
- Железо — только имеющиеся платы ESP32 (классический ESP32, не S2/S3/C3/C6) и мелкие модули с AliExpress; готовые аудио-платы (Audio Kit) не предлагать.
- Мостовые усилители (MAX98357A, PAM8403, NS4150) не подходят: у гарнитуры общий провод.

## Сборка

Приложение (JDK 17–23, Android SDK):

```bash
./gradlew assembleDebug testDebugUnitTest
```

Прошивка: Arduino IDE 2 / arduino-cli из IDE (`C:\Program Files\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe`), ядро esp32 2.0.17, плата ESP Dev Module, Partition Scheme «Huge APP (3MB No OTA)», библиотека ESP32-A2DP 1.8.11 (установлена в `Documents\Arduino\libraries`).

```bash
arduino-cli compile --fqbn esp32:esp32:esp32:PartitionScheme=huge_app firmware/h0_radio_test
arduino-cli upload -p COM5 --fqbn esp32:esp32:esp32:PartitionScheme=huge_app firmware/h0_radio_test
```

Плата HW-394 на CH340, у владельца обычно COM5. Прошивать только с выключенным аккумулятором.
