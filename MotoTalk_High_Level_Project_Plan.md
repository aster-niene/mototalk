# MotoTalk — High-Level Project Plan

**Last-checked:** 2026-10-06  
**Status:** Concept / Technical validation  
**Target platform:** Android  
**Primary use case:** Motorcycle rider-to-rider intercom without Internet  
**Language:** Russian + key technical terms in English

---

## 1. Идея проекта

**MotoTalk** — Android-приложение для мотоциклистов, которое использует телефон как центральный аудио-хаб.

Каждый шлем подключается только к своему телефону по Bluetooth.  
Телефоны устанавливают прямое P2P-соединение между собой без доступа к Интернету.

Базовая схема:

```text
Helmet A ⇄ Phone A ⇄ P2P ⇄ Phone B ⇄ Helmet B
                ↑                 ↑
             Music             Music
             Navigation        Navigation
```

Телефон должен одновременно:

- принимать звук с микрофона Bluetooth-гарнитуры;
- передавать речь второму телефону;
- принимать речь второго райдера;
- воспроизводить её в шлем;
- сохранять совместимость с:
  - музыкой;
  - навигацией;
  - системными телефонными звонками;
- автоматически восстанавливать соединение после краткого разрыва.

Ключевой принцип:

> Шлемы ничего не знают друг о друге.  
> Интерком реализуется полностью на уровне телефонов.

---

# 2. Основная архитектура

## 2.1. Логическая схема

```text
                    MotoTalk
                       │
          ┌────────────┴────────────┐
          │                         │
    Android Audio              P2P Transport
          │                         │
 Bluetooth Headset              Other Phone
          │
        Helmet
```

Основные подсистемы:

```text
app
│
├── audio
│   ├── AudioCapture
│   ├── AudioPlayback
│   ├── AudioRouter
│   ├── AudioProcessor
│   └── Codec
│
├── transport
│   ├── PeerDiscovery
│   ├── PeerConnection
│   ├── PacketTransport
│   └── ReconnectManager
│
├── intercom
│   ├── IntercomSession
│   ├── JitterBuffer
│   └── VoiceSession
│
├── bluetooth
│   ├── DeviceManager
│   └── AudioDeviceManager
│
├── service
│   └── RideService
│
└── ui
```

Важно:

- networking и audio engine не должны быть связаны напрямую;
- transport должен быть заменяемым;
- codec должен быть заменяемым;
- Bluetooth routing должен быть отдельным слоем.

---

# 3. Критический технический риск

Самая сложная часть проекта — **не соединение телефон ↔ телефон**, а работа:

```text
Android ↔ Bluetooth helmet headset
```

Особенно при одновременном использовании:

```text
Music
+
Navigation
+
Intercom voice
+
Bluetooth microphone
```

Для Bluetooth Classic при активации микрофона гарнитура часто переходит:

```text
A2DP → HFP/SCO
```

Это означает:

- снижение качества музыки;
- mono audio;
- более узкую полосу частот;
- возможные ограничения Android audio routing.

Поэтому проект необходимо начинать не с UI и не с P2P, а с проверки Bluetooth audio feasibility.

---

# 4. Phase 0 — Bluetooth Audio Feasibility

## Цель

Проверить, способен ли Android использовать конкретную Bluetooth-гарнитуру как полноценный VoIP endpoint.

### Тестовая схема

```text
EJEAS microphone
       ↓
Android AudioRecord
       ↓
MotoTalk
       ↓
Android AudioTrack
       ↓
EJEAS speaker
```

## Проверить

- определяется ли гарнитура как communication device;
- можно ли выбрать её через Android audio routing;
- доступен ли микрофон;
- доступен ли динамик;
- работает ли full-duplex;
- какая задержка;
- какое качество речи;
- что происходит со Spotify;
- что происходит с Google Maps;
- что происходит при входящем телефонном звонке;
- возвращается ли звук обратно после завершения звонка;
- что происходит при отключении/повторном подключении Bluetooth.

## Definition of Done

Пользователь говорит в микрофон шлема → приложение получает звук → приложение возвращает его в шлем.

Если этот этап не работает приемлемо, дальнейшая разработка приостанавливается до решения проблемы Bluetooth-аудиостека.

---

# 5. Phase 1 — P2P Transport

После успешной проверки Bluetooth подключается второй Android-телефон.

Первая версия:

```text
Phone A
   ⇅
Nearby Connections
P2P_POINT_TO_POINT
   ⇅
Phone B
```

Альтернативный production-вариант:

```text
Wi-Fi Direct
+
UDP / RTP
```

## Требования

Соединение должно работать при:

- отключённом мобильном Интернете;
- отсутствии Wi-Fi router;
- отсутствии общего access point;
- отсутствии серверной инфраструктуры.

Телефоны должны:

1. обнаружить друг друга;
2. выполнить pairing;
3. создать direct connection;
4. передавать bidirectional data;
5. автоматически переподключаться после краткого разрыва.

---

# 6. Phase 2 — Первый Intercom

Объединяются Bluetooth audio и P2P transport.

```text
Helmet A
   │
   ▼
AudioRecord
   │
   ▼
Encoder
   │
   ▼
P2P
   │
   ▼
Decoder
   │
   ▼
AudioTrack
   │
   ▼
Helmet B
```

Параллельно:

```text
Helmet B → Phone B → Phone A → Helmet A
```

## Требование

Связь должна быть:

```text
Full duplex
```

Не push-to-talk.

Это первый функциональный MVP.

---

# 7. Phase 3 — Audio Codec

После доказательства транспортного слоя сырой PCM заменяется на **Opus**.

Стартовые параметры:

```text
Codec: Opus
Channels: Mono
Sample rate: 16–24 kHz
Frame size: 20 ms
Bitrate: ~24–40 kbit/s
```

Добавить:

- sequence number;
- timestamp;
- jitter buffer;
- packet-loss handling;
- silence detection;
- decoder recovery;
- adaptive bitrate — позже.

---

# 8. Phase 4 — Voice Processing

Мотоциклетная акустическая среда сложная:

```text
Wind
Engine
Road noise
Helmet speaker
Voice
```

Минимальный pipeline:

```text
Microphone
    ↓
High-pass filter
    ↓
Noise Suppression
    ↓
Automatic Gain Control
    ↓
Echo Cancellation
    ↓
Voice Activity Detection
    ↓
Opus
```

Предпочтительный подход:

- использовать готовый DSP stack;
- рассмотреть WebRTC Audio Processing Module;
- не реализовывать AEC/NS/AGC самостоятельно без необходимости.

---

# 9. Phase 5 — Music + Navigation + Intercom

Это отдельный milestone.

Цель:

```text
Spotify
Google Maps
MotoTalk voice
       ↓
Bluetooth helmet
```

Желаемое поведение:

## Никто не говорит

```text
Music: 100%
```

## Навигатор говорит

```text
Music: 30%
Navigation: 100%
```

## Второй райдер говорит

```text
Music: 20–30%
Intercom: 100%
```

## Разговор закончен

```text
Music → плавно возвращается к 100%
```

Приложение не должно само становиться музыкальным плеером.

Оно должно сосуществовать с:

- Spotify;
- YouTube Music;
- Google Maps;
- другими navigation apps.

---

# 10. Connection State Machine

Нельзя ограничиваться двумя состояниями:

```text
Connected
Disconnected
```

Нужна полноценная state machine.

Основной flow:

```text
IDLE
 ↓
SEARCHING
 ↓
PEER_FOUND
 ↓
CONNECTING
 ↓
CONNECTED
 ↓
AUDIO_READY
 ↓
INTERCOM_ACTIVE
```

Ошибочные состояния:

```text
PEER_LOST
AUDIO_DEVICE_LOST
BLUETOOTH_DISABLED
P2P_FAILED
PERMISSION_LOST
```

Автоматическое восстановление:

```text
Peer lost
    ↓
reconnect
    ↓
CONNECTED
```

Пользователь не должен доставать телефон для reconnect.

---

# 11. Background Operation

Типичный сценарий:

```text
START RIDE
```

Пользователь блокирует телефон и кладёт его в карман.

Приложение должно продолжать:

- P2P communication;
- microphone capture;
- playback;
- Bluetooth audio routing;
- reconnect;
- health monitoring соединения.

Предполагаемая архитектура:

```text
Foreground Service
        │
        ├── AudioEngine
        ├── PeerConnection
        └── ConnectionManager
```

Пример notification:

```text
MotoTalk
Connected to Ivan
Intercom active
```

---

# 12. Pairing UX

Первое соединение:

```text
Kseniia
   ⇄
Ivan

Code:
482731

Confirm?
```

После подтверждения устройства запоминают друг друга.

Последующие поездки:

```text
Helmet connected
      ↓
MotoTalk starts
      ↓
Known peer detected
      ↓
Auto-connect
      ↓
"Intercom connected"
```

---

# 13. Voice Announcements

Поскольку телефон обычно находится в кармане, важные события должны озвучиваться.

Примеры:

```text
"Intercom connected."
"Ivan disconnected."
"Reconnecting."
"Bluetooth headset disconnected."
"Battery low."
```

UI во время движения должен быть вторичным.

---

# 14. Safety-oriented UX

Основной принцип:

> После начала движения MotoTalk не должен требовать взаимодействия с экраном.

Минимизировать:

- меню;
- confirmation dialogs;
- ручной reconnect;
- сложные настройки;
- действия во время движения.

Главная кнопка:

```text
START RIDE
```

После неё приложение должно выполнять максимум действий автоматически.

---

# 15. Minimal UI

Для MVP достаточно одного основного экрана.

```text
┌─────────────────────────┐
│ MotoTalk                │
│                         │
│ Helmet: EJEAS V6   ✓    │
│ Rider: Ivan        ✓    │
│                         │
│ Ping: 34 ms             │
│ Audio: Active           │
│                         │
│     [ START RIDE ]      │
└─────────────────────────┘
```

Экран pairing:

```text
Nearby riders

Ivan's S23
[ CONNECT ]
```

---

# 16. Протокол

Даже для двух телефонов нужен небольшой protocol layer.

Типы сообщений:

```text
CONTROL
AUDIO
PING
PONG
DEVICE_INFO
SESSION_START
SESSION_END
```

Пример audio packet:

```text
version
sessionId
sequenceNumber
timestamp
payloadLength
Opus payload
```

Это позволит позже:

- менять transport;
- добавлять группы;
- диагностировать packet loss;
- внедрять encryption/session negotiation;
- добавлять protocol versioning.

---

# 17. Метрики и диагностика

С первой рабочей версии логировать:

```text
RTT
Packet loss
Jitter
Audio underruns
Audio overruns
Reconnect count
Bluetooth device changes
Codec bitrate
Audio route changes
Session duration
```

Без этого невозможно диагностировать проблемы вида:

> «На автобане иногда заикается».

---

# 18. Latency Budget

Ориентировочная задержка:

```text
Bluetooth microphone       20–40 ms
Capture / buffering        ~20 ms
Opus                       ~10–20 ms
P2P network                ~5–20 ms
Jitter buffer              40–60 ms
Decode / output            20–40 ms
──────────────────────────────────
TOTAL                     ~115–200 ms
```

Цель:

```text
< 200 ms end-to-end
```

Для обычного разговора это приемлемо.

---

# 19. Road Tests

Тестирование должно идти постепенно.

## Stage A — помещение

```text
2 человека
2 телефона
гарнитуры
несколько метров
```

Проверяем:

- audio quality;
- latency;
- reconnect;
- Bluetooth routing.

---

## Stage B — улица

```text
10–50 метров
```

Проверяем:

- стабильность P2P;
- interference;
- reconnect;
- packet loss.

---

## Stage C — город

```text
2 motorcycles
urban traffic
```

Проверяем:

- реальные помехи;
- handoff между Wi-Fi/Bluetooth transport;
- navigation;
- music coexistence.

---

## Stage D — 80 km/h

Проверяем:

- wind noise;
- DSP;
- speech intelligibility.

---

## Stage E — 100–130 km/h

Проверяем предел системы.

Особенно:

- noise suppression;
- microphone quality;
- jitter;
- reconnect;
- voice intelligibility.

---

# 20. MVP 1.0

MotoTalk 1.0 должен уметь:

- Android ↔ Bluetooth headset;
- два Android-телефона;
- direct P2P;
- работу без Internet;
- full-duplex voice;
- Opus;
- automatic reconnect;
- background operation;
- audio focus / music ducking;
- navigation coexistence;
- remembered peer;
- минимальный UI;
- базовую диагностику.

И ничего больше.

---

# 21. После MVP

## MotoTalk 1.5

Добавить:

```text
Push-to-talk
Mute
Volume control
Voice activation threshold
Connection quality indicator
Diagnostics screen
```

---

## MotoTalk 2 — группы

Пример:

```text
A
├── B
├── C
└── D
```

Нужно решить topology.

Основные варианты:

```text
Mesh
vs
Star
```

Предпочтительный вариант для начала:

```text
Star topology
```

Один телефон становится leader/hub.

---

## MotoTalk 3

Возможные дополнительные функции:

```text
Location sharing
Ride group
SOS
Crash detection
Voice commands
Intercom recording
Ride history
Battery telemetry
```

Все эти функции вторичны по сравнению с качеством базовой голосовой связи.

---

# 22. Финальный Roadmap

```text
0. Bluetooth audio feasibility
            ↓
1. Local audio loopback
            ↓
2. Phone-to-phone P2P
            ↓
3. PCM full-duplex
            ↓
4. Opus
            ↓
5. Jitter / packet loss handling
            ↓
6. Noise suppression / AEC
            ↓
7. Background service
            ↓
8. Spotify / Maps coexistence
            ↓
9. Auto reconnect
            ↓
10. Minimal UI
            ↓
11. Real motorcycle tests
            ↓
         MVP 1.0
```

---

# 23. Приоритеты проекта

## P0 — блокирующие

```text
Bluetooth microphone capture
Bluetooth speaker output
Full duplex
Acceptable latency
Stable P2P
Background execution
Reconnect
```

## P1 — обязательные для MVP

```text
Opus
Jitter buffer
Noise suppression
Audio focus
Navigation coexistence
Basic pairing
Metrics
```

## P2 — после MVP

```text
Groups
PTT
Location
Crash detection
Recording
Advanced UI
Cloud features
```

---

# 24. Основной engineering principle

Не начинать проект с:

- красивого UI;
- аккаунтов;
- серверов;
- групповых чатов;
- геолокации;
- собственной электроники.

Начать с одного вопроса:

> Может ли Android + выбранная Bluetooth-гарнитура работать как стабильный bidirectional VoIP endpoint с приемлемой задержкой и качеством?

Если ответ **да**, всё остальное — нормальная software engineering задача.

Если ответ **нет**, сначала решается Bluetooth audio layer.

---

# 25. Suggested First Implementation Task

Первый отдельный implementation spike:

```text
S23 Ultra
   ⇅
EJEAS V6 Pro+
```

Минимальная функциональность:

```text
Bluetooth headset selected
        ↓
AudioRecord
        ↓
PCM
        ↓
AudioTrack
        ↓
Same Bluetooth headset
```

Параллельно вручную проверить:

```text
Spotify
Google Maps
Incoming call
Bluetooth reconnect
Screen locked
App background
```

После этого можно переходить к P2P.

---

# 26. Source Links

Проверить актуальные Android API перед реализацией:

- Android Bluetooth / Audio routing:  
  https://developer.android.com/develop/connectivity/bluetooth

- AudioManager / communication devices:  
  https://developer.android.com/reference/android/media/AudioManager

- Android audio focus:  
  https://developer.android.com/media/optimize/audio-focus

- Nearby Connections:  
  https://developers.google.com/nearby/connections/overview

- Wi-Fi Direct:  
  https://developer.android.com/develop/connectivity/wifi/wifi-direct

- Android Core Telecom / VoIP integration:  
  https://developer.android.com/develop/connectivity/telecom

- Opus codec:  
  https://opus-codec.org/

- WebRTC Audio Processing:  
  https://webrtc.org/

---

# 27. Мой выбор → аргументы

## Transport для первого прототипа

**Nearby Connections**

Почему:

- работает offline;
- минимум инфраструктуры;
- discovery встроен;
- pairing встроен;
- удобно для быстрого proof-of-concept.

Для production при необходимости можно перейти на:

```text
Wi-Fi Direct + UDP/RTP
```

---

## Codec

**Opus**

Почему:

- подходит для speech;
- низкая задержка;
- устойчив к packet loss;
- хорошо работает на низких bitrate;
- де-факто стандарт для realtime voice.

---

## Audio processing

**WebRTC Audio Processing Module**

Почему:

- AEC;
- noise suppression;
- AGC;
- проверенный realtime stack.

---

## Архитектурный принцип

**Software-only first.**

Собственная электроника / USB-C / BLE audio hardware имеет смысл только если Android Bluetooth Classic окажется фундаментальным ограничением для требуемого качества.

