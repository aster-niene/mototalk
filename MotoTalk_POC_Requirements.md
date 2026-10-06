# MotoTalk — POC Requirements

**Last-checked:** 2026-10-06  
**Status:** v2 — по итогам интервью + фактчек по документации Android / Nearby / AOSP / EJEAS (§13)  
**Основа:** [MotoTalk_High_Level_Project_Plan.md](MotoTalk_High_Level_Project_Plan.md)

---

## 1. Цель POC

Два райдера в шлемах с **EJEAS V6 Pro+**, у каждого **Samsung на Android 14+** в кармане с заблокированным экраном.  
Они разговаривают **full-duplex** шлем ↔ шлем через телефоны, без Интернета, на дистанции **до ~50 м**.  
При этом **Spotify / Google Maps продолжают звучать** и приглушаются, пока говорит партнёр.

Именно связка «музыка + навигация + голос одновременно» — причина делать приложение вместо встроенного интеркома EJEAS.

> **Важно (подтверждено по AOSP):** пока SCO открыт, Android отправляет музыку и навигацию в тот же SCO-канал — **моно, в полосе телефонного звонка**. Стерео A2DP во время сессии не будет. Это поведение платформы, а не баг. Приемлемо ли это на слух — главный вопрос стоп-точки после Phase 0 (§11).

POC отвечает на вопрос плана (§24): может ли Android + EJEAS быть стабильным bidirectional VoIP endpoint, и что при этом происходит с музыкой.

---

## 2. Зафиксированные решения

| # | Тема | Решение | Отличие от плана |
|---|---|---|---|
| D1 | Объём | Phase 0 + 1 + 2: loopback → P2P → **raw PCM full-duplex** | — |
| D2 | Железо | S23 Ultra + Samsung Galaxy S21 или новее (Android 14+); 2× EJEAS V6 Pro+ (Bluetooth 5.1). Модель и версию One UI каждого телефона пишем в лог | — |
| D3 | Ценность | Музыка + навигация + интерком одновременно | Уточнено |
| D4 | SCO / музыка | В POC SCO открыт **всю сессию**. Время переключения A2DP↔SCO и качество музыки в SCO **замеряем** (M5–M8), режим для MVP выбираем по данным | Новое |
| D5 | Передача голоса | **VAD-гейт** на передающей стороне + переключатель «Передавать всегда» | Уточнено: VAD работает поверх открытого SCO |
| D6 | Музыка / навигация | **Ducking уже в POC**: на принимающей стороне музыка приглушается, только пока партнёр говорит (флаг речи, §5) | Phase 5 → POC |
| D7 | Дальность | Цель — до ~50 м. Это гипотеза: номинал Nearby «~100 м» дан без учёта тел и карманов. Проверяется критерием §8.6 | Новое |
| D8 | Транспорт | **Nearby Connections**, `P2P_POINT_TO_POINT`, за интерфейсом `PeerTransport` | — |
| D9 | Фон | **Foreground service**, работа с заблокированным экраном | §11 → POC |
| D10 | Reconnect | **Автоматический**, к запомненному пиру, без подтверждений | §10 → POC (соответствует P0) |
| D11 | Входящий звонок | **Пауза и автовозврат**; у партнёра статус «Rider in call» | Новое |
| D12 | Задержка | **Без порога** — измеряем (§7.1) и оцениваем на слух | Цель < 200 мс переносится на этап Opus |
| D13 | Telecom | Core-Telecom / `ConnectionService` / FGS-тип `phoneCall` в POC **не используем**. Telecom держит audio focus на весь VoIP-звонок и глушит `USAGE_MEDIA`: Spotify встанет на паузу на всю поездку, а ducking станет невозможен (противоречит D3/D6). Кроме того, с Telecom нельзя вызывать `setCommunicationDevice()`. Теряем системный hold и call waiting — их заменяет FR-11. Пересматриваем на MVP | Новое |

---

## 3. Вне рамок POC

- Opus и любой кодек — только PCM 16 kHz.
- WebRTC APM (NS / AEC / AGC) — полагаемся на CVC гарнитуры и то, что даёт платформа.
- Группы, push-to-talk, mute, регулировка громкости.
- Голосовые оповещения (§13 плана) — состояние видно в уведомлении.
- Шифрование; версионирование протокола сверх поля `version`.
- Wi-Fi Direct + UDP — только как будущая замена за `PeerTransport`.
- Регистрация интеркома как звонка в Telecom (D13).
- VoIP-звонки других приложений (WhatsApp и т.п.) как повод для паузы.
- Восстановление после гибели процесса.
- Состояния плана §10 `BLUETOOTH_DISABLED` / `P2P_FAILED` / `PERMISSION_LOST` как отдельные ветки (Bluetooth выключен → `LocalAudio = DEVICE_LOST`; при отзыве разрешения Android сам завершает процесс).
- Красивый UI.

---

## 4. Функциональные требования

Всё аудио (включая Loopback), транспорт, reconnect и state machine живут в `RideService` (FR-8). Activity только наблюдает.

### FR-1. Аудиомаршрут через гарнитуру

**Открытие маршрута** (START RIDE или Loopback):

1. `setMode(MODE_IN_COMMUNICATION)`. Нужен `MODIFY_AUDIO_SETTINGS`: без него вызов молча игнорируется.
2. Сразу запустить `AudioTrack` (`USAGE_VOICE_COMMUNICATION`, `CONTENT_TYPE_SPEECH`, пишет тишину) и `AudioRecord` (источник `VOICE_COMMUNICATION`).
3. Найти в `getAvailableCommunicationDevices()` устройство `TYPE_BLUETOOTH_SCO` и вызвать `setCommunicationDevice()`. `true` означает только «запрос принят», а не «SCO поднят».
4. Ждать `OnCommunicationDeviceChangedListener` с типом `TYPE_BLUETOOTH_SCO`, таймаут 10 с. При таймауте — `clearCommunicationDevice()` и повтор.
5. Только после этого `LocalAudio = READY`.

`startBluetoothSco()` не используем: `setCommunicationDevice()` сам поднимает SCO.

**Удержание режима.** Android считает приложение владельцем `MODE_IN_COMMUNICATION`, только пока у него работает `AudioTrack` с `USAGE_VOICE_COMMUNICATION` или `AudioRecord` с `VOICE_COMMUNICATION`. После `setMode()` даётся ~6 с, затем режим откатывается в `MODE_NORMAL`. Поэтому оба потока работают **непрерывно всю сессию**:
- без голоса `AudioTrack` пишет тишину;
- `AudioRecord` всегда читает кадры (для VAD), но не всегда их отправляет;
- никаких `pause()`/`stop()` между фразами;
- потоки останавливаются только на время сотового звонка (FR-11).

**Потеря SCO.** SCO поднимается как «виртуальный звонок», поэтому шлем видит активный вызов. Нажатие «сброс вызова» на шлеме может разорвать SCO, не отключая сам шлем, и `AudioDeviceCallback` при этом не сработает. Правило: любое событие `OnCommunicationDeviceChangedListener` с типом ≠ `TYPE_BLUETOOTH_SCO` во время сессии переводит в `LocalAudio = DEVICE_LOST`. Что делаем в этом состоянии:
- **TX** не отправляем, иначе партнёр услышит шум кармана с микрофона телефона.
- **RX** — в `AudioTrack` пишем тишину, иначе голос партнёра пойдёт в динамик телефона.
- Вызываем `clearCommunicationDevice()` и повторяем шаги 3–4 с backoff 1–5 с, пока SCO есть в списке устройств и нет сотового звонка.

**Шлем выключен / вернулся.** Выбор устройства связи после отключения шлема сам не восстанавливается. По `AudioDeviceCallback.onAudioDevicesAdded` с `TYPE_BLUETOOTH_SCO` повторяем шаги 3–4 с повторами 1–5 с: HFP может стать активным через несколько секунд после подключения.

**Stop:** `abandonAudioFocusRequest()` → остановить потоки → `clearCommunicationDevice()` → `setMode(MODE_NORMAL)`. Без `clearCommunicationDevice()` SCO может остаться поднятым, а A2DP — приостановленным.

**Формат:** всегда **16 kHz mono PCM16**.
- `AudioRecord.getSampleRate()` возвращает запрошенную частоту и ничего не говорит о кодеке SCO: при CVSD (narrowband, 8 kHz) платформа сама передискретизирует 8→16 kHz.
- WB/NB определяем отдельно по M2.
- Ветки «работаем на 8 kHz» нет. Если `AudioRecord` на 16 kHz не инициализируется — это блокер.

### FR-2. Loopback-режим (Phase 0)

- Кнопка «Loopback»: микрофон шлема → тот же шлем, без сети, внутри `RideService`.
- Показ: запрошенный sample rate, `getRoutedDevice()` для `AudioRecord` и `AudioTrack` (ожидается `TYPE_BLUETOOTH_SCO`), размеры буферов, результат M2 (WB/NB).
- Кнопка **«Record 10 s»** — сохраняет захват с шлема в WAV 16 kHz, экспорт через Share (для M2).
- Кнопка **«Duck test»** — `requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` на 5 с, затем `abandonAudioFocusRequest()` (для M7–M8 без второго телефона).

### FR-3. Поиск и сопряжение

- `deviceId` — случайный UUID (16 байт), генерируется при первом запуске и хранится локально (SharedPreferences, в бэкап не попадает).
- `endpointInfo = [version u8][deviceId 16 байт][имя UTF-8 ≤ 15 байт]`, всего ≤ 32 байт. Используем только перегрузки с `byte[]`: `startAdvertising(endpointInfo, SERVICE_ID, …, AdvertisingOptions)` и `requestConnection(endpointInfo, endpointId, …, ConnectionOptions)`. `SERVICE_ID = applicationId`.
- Strategy `P2P_POINT_TO_POINT`. В `SEARCHING` оба телефона одновременно advertise + discover.
- Список найденных райдеров (имя из `endpointInfo`) → тап «Connect».
- Оба телефона показывают `ConnectionInfo.getAuthenticationDigits()` (4 цифры) → «Accept» на обоих. Встречные запросы Nearby разрешает сам.
- После `onConnectionResult(STATUS_OK)` оба телефона вызывают `stopDiscovery()` и `stopAdvertising()`. Discovery — «тяжёлая» радио-операция и повышает риск обрыва уже установленного соединения.
- Пир запоминается как `{deviceId, имя}`. `endpointId` не храним — он меняется (`onEndpointIdRotation`).
- Первичное сопряжение — только с включённым экраном. Reconnect (FR-10) обязан работать с заблокированным.
- `ConnectionsClient` создаётся в `RideService` через `Nearby.getConnectionsClient(applicationContext)`, не через вариант с Activity.

### FR-4. Передача аудио

- Кадр **20 мс** = 320 сэмплов = 640 байт + 13 байт заголовка (§5) = 653 байта.
- 50 кадров/с ≈ **32.7 KB/s ≈ 261 kbit/s в каждую сторону** при постоянной передаче, ≈ 65 KB/s в обе стороны плюс накладные расходы Nearby.
- Для сравнения `BandwidthInfo.Quality`:
  - `LOW` ≈ 5 KB/s — поток не пройдёт;
  - `MEDIUM` ≈ 60–200 KB/s — впритык;
  - `HIGH` — с запасом.
- Каждый кадр — отдельный `BYTES` payload. Это сохраняет границы кадров и позволяет отправителю сбрасывать кадры. Все сообщения протокола тоже только `BYTES`: порядок гарантирован только внутри одного типа payload.
- **Лимит in-flight.** `sendPayload()` лишь ставит payload в очередь Nearby. Если канала не хватает, очередь растёт и задержка копится вплоть до разрыва. Поэтому:
  - `payloadId` добавляется в множество in-flight при `sendPayload()`;
  - удаляется по `onPayloadTransferUpdate()` исходящего payload со статусом `SUCCESS` / `FAILURE` / `CANCELED`;
  - при разрыве соединения множество очищается;
  - если in-flight ≥ N (по умолчанию 10 кадров ≈ 200 мс, настраивается), новый AUDIO-кадр не отправляется, `tx_dropped++`.
- В `PayloadCallback` никакой обработки: только положить кадр в очередь приёма.

### FR-5. Минимальный playout-буфер

- Глубина в кадрах: `target = 3` (60 мс), настраивается в UI в диапазоне 1–10.
- **Re-prime:** воспроизведение начинается при `depth ≥ target` — в начале каждой фразы и после underrun.
- Кадры pre-roll (FR-6) принимаются целиком.
- **Overflow:** если `depth > max`, где `max = target + pre-roll + 3` (по умолчанию 3 + 5 + 3 = 11 кадров), сбрасываем самые старые кадры до `target` и увеличиваем `drops_overflow`. Это же правило компенсирует дрейф часов (20 ppm ≈ 36 мс за 30 мин).
- Опоздавший кадр (`seq` меньше следующего к воспроизведению) и дубликат — отбрасываем.
- **Underrun** считаем, только если последний принятый кадр был с `bit0 = 1` (речь) и следующий не пришёл вовремя. Пауза между фразами — не underrun и не потеря. Отдельно считаем число фраз (talkspurts).
- При новом соединении (HELLO) буфер и ожидаемый `seq` сбрасываются.
- Раз в 10 с в лог: средняя глубина, `drops_overflow`, underruns, межпакетный интервал (p50 / p95 / max). По ним выбираем `target`.

### FR-6. VAD-гейт на передаче

- VAD считается **всегда**, в обоих режимах. Детектор по энергии кадра.
- Порог = шумовой фон + N dB. Шумовой фон — медленно отслеживаемый минимум энергии кадров (окно несколько секунд). N — слайдер в UI. В диагностике видны шумовой фон и энергия кадра в dBFS.
- Hangover ~500 мс после конца речи.
- Pre-roll **100 мс** (5 кадров) из кольцевого буфера при открытии гейта, чтобы не срезать начало фразы (SCO уже открыт, звук доступен).
- `flags.bit0` = «речь»: решение VAD с учётом hangover; кадры pre-roll тоже помечаются 1.
- Режим **«Гейт»**: AUDIO отправляется только при `bit0 = 1`, плюс один финальный кадр с `bit0 = 0` при закрытии гейта. PING продолжается.
- Режим **«Передавать всегда»**: AUDIO идёт всегда, `bit0` по-прежнему = решение VAD.
- В режиме «Гейт» задержка каждой фразы больше на длину pre-roll, поэтому задержку меряем в режиме «Передавать всегда» (§7.1).
- Счётчик «мой гейт открылся, пока играет голос партнёра» — признак эха или ложных срабатываний VAD (M13).

### FR-7. Ducking музыки

- Один `AudioFocusRequest` на сессию: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, `AudioAttributes(USAGE_VOICE_COMMUNICATION, CONTENT_TYPE_SPEECH)`, свой `OnAudioFocusChangeListener`, без `setWillPauseWhenDucked`.
- Запрос и `abandonAudioFocusRequest()` — одним и тем же объектом, только из `RideService`. При targetSdk ≥ 35 фокус выдаётся только top app или при работающем FGS, иначе `AUDIOFOCUS_REQUEST_FAILED`. Результат каждого запроса логируем.
- **Триггер:** ≥ 2 принятых кадра подряд с `bit0 = 1` при `INTERCOM_ACTIVE` → запрос фокуса. 800 мс без кадров с `bit0 = 1` → abandon. Сам факт прихода кадров ducking не включает: иначе в режиме «Передавать всегда» музыка была бы приглушена всю поездку.
- Свой `AudioTrack` по событиям фокуса никогда не останавливаем и не приглушаем — только логируем.
- Приглушение делает система: примерно −14 dB с рампой ~500 мс, уровень приложение не задаёт.
- Плееры с `CONTENT_TYPE_SPEECH` (подкасты, подсказки Maps) система не приглушает. Такие приложения решают сами и могут встать на паузу.
- Если −14 dB в шлеме мало — фиксируем как находку POC.
- Диагностика: `registerAudioPlaybackCallback()` — для чужих плееров логируем usage, contentType и state (`STARTED` / `PAUSED` / `STOPPED`). Пауза Spotify так видна, а duck — нет.

### FR-8. Foreground service

- Перед `startForegroundService()` Activity запрашивает runtime-разрешения (§6). Какая кнопка от каких разрешений зависит:

  | Кнопка | Нужны разрешения |
  |---|---|
  | Loopback | `RECORD_AUDIO` + `BLUETOOTH_CONNECT` |
  | START RIDE | дополнительно `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `NEARBY_WIFI_DEVICES` |

  `POST_NOTIFICATIONS` запрашиваем, но не блокируем: без него FGS работает, уведомление видно только в Task Manager.
- Перед START RIDE проверяем, что включены Bluetooth и Wi-Fi (`BluetoothAdapter.isEnabled()`, `WifiManager.isWifiEnabled()`). Wi-Fi выключен — предупреждение на экране и запись в лог.
- Манифест: `<service android:name=".service.RideService" android:exported="false" android:foregroundServiceType="microphone|connectedDevice"/>`.
- В `onStartCommand` сразу `ServiceCompat.startForeground(this, NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE or FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` в `try/catch` (`SecurityException`, `ForegroundServiceStartNotAllowedException`). При ошибке → `SERVICE_ERROR(reason)`, сообщение в UI, `stopSelf()`.
- Сервис стартует **только из видимой Activity** (START RIDE / Loopback) и живёт до Stop.
- За сессию **не вызываем** `stopForeground()` и повторный `startForeground()`: повторный вызов из фона заново проверяет while-in-use, и микрофон замолкает. Уведомление обновляем через `NotificationManager.notify(тот же id, …)`.
- `onStartCommand` возвращает `START_NOT_STICKY`. Гибель процесса в POC не восстанавливаем; в логе это видно как сессия без записи STOP.
- Уведомление: состояние по трём осям (FR-9), имя пира, кнопка **Stop**.
- Флаг «микрофон заглушён»: `AudioRecord.registerAudioRecordingCallback()` → `isClientSilenced()`, в диагностику.

### FR-9. State machine — три независимые оси

| Ось | Значения |
|---|---|
| **Link** | `IDLE` · `SEARCHING` · `CONNECTING` · `CONNECTED` (HELLO партнёра получен) · `RECONNECTING` |
| **LocalAudio** | `OFF` · `ROUTING` · `READY` · `DEVICE_LOST` · `PAUSED_BY_CALL` |
| **RemoteAudio** (из HELLO / STATE партнёра) | `UNKNOWN` · `READY` · `DEVICE_LOST` · `IN_CALL` · `STOPPED` |

`INTERCOM_ACTIVE = Link=CONNECTED ∧ LocalAudio=READY ∧ RemoteAudio=READY` — вычисляется, не хранится. Только в этом состоянии отправляем AUDIO и включаем ducking.

Правила:
- START RIDE: сразу `LocalAudio = ROUTING` (SCO открывается до поиска) и параллельно `Link = SEARCHING`.
- Потеря линка не меняет `LocalAudio`: SCO остаётся открытым. Потеря шлема не меняет `Link`.
- Звонок не меняет `Link`: reconnect продолжается во время звонка.
- `PAUSED_BY_CALL` входится из `READY` или `DEVICE_LOST`; после звонка → `READY` или `DEVICE_LOST`, если шлема нет.
- Stop: отправить BYE, закрыть всё, `Link = IDLE`, `LocalAudio = OFF`.
- Отдельно `SERVICE_ERROR(PERMISSION_MISSING | FGS_START_DENIED)` — сессия не стартовала.
- UI и уведомление показывают все три оси. Каждое изменение любой оси пишется в лог с timestamp.

### FR-10. Автоматический reconnect

**Детект обрыва (PEER_LOST):**
- Первое из двух событий: `onDisconnected()` или ни одного входящего сообщения (AUDIO / PING / PONG / STATE) дольше `T_dead`. `T_dead` по умолчанию 5 с, настраивается.
- На keep-alive Nearby не полагаемся: он может сообщить об обрыве только через десятки секунд.
- PING идёт раз в 1 с всегда, в том числе при закрытом гейте и во время звонка.
- По таймауту: `disconnectFromEndpoint(endpointId)` → `Link = RECONNECTING`.
- В лог: причина (timeout / `onDisconnected`), время детекта (последний входящий пакет → решение об обрыве), время reconnect (решение → первый PONG/AUDIO после нового HELLO).

**Восстановление** — роли в `RECONNECTING` фиксированы. Это убирает BT inquiry на одном из телефонов при открытом SCO.
- Телефон с меньшим `deviceId` только ищет (`startDiscovery()`), с большим — только рекламируется (`startAdvertising()`).
- Ищущий в `onEndpointFound` сверяет `deviceId` из `DiscoveredEndpointInfo.getEndpointInfo()` с запомненным; совпал → `requestConnection()`.
- В `onConnectionInitiated` оба сверяют `deviceId` из `ConnectionInfo.getEndpointInfo()`. Совпал → `acceptConnection()` без UI, иначе `rejectConnection()`.
- Коды Nearby:

  | Код | Действие |
  |---|---|
  | `STATUS_ALREADY_ADVERTISING` / `STATUS_ALREADY_DISCOVERING` | считаем успехом |
  | `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` | → `CONNECTED` |
  | `STATUS_OUT_OF_ORDER_API_CALL` | `stopAllEndpoints()` и повтор |
- Ошибка `requestConnection` или `onConnectionResult ≠ STATUS_OK` → `stopDiscovery()`, затем `startDiscovery()` с backoff 0.5 → 1 → 2 → 5 с, дальше каждые 5 с. Число попыток не ограничено, пока сервис запущен.
- После `CONNECTED` → `stopDiscovery()` и `stopAdvertising()`.
- Аудио-маршрут при этом не трогаем.
- Получен **BYE**: `RemoteAudio = STOPPED`, `Link = RECONNECTING` (ждём, пока партнёр снова нажмёт START RIDE), в уведомлении «Partner stopped».

### FR-11. Входящий / исходящий телефонный звонок

**Детект:**
- `AudioManager.addOnModeChangedListener()` (API 31, разрешений не требует) плюс разовая проверка `getMode()` при старте сессии.
- Звонок активен, если mode ∈ {`MODE_RINGTONE`, `MODE_IN_CALL`, `MODE_CALL_SCREENING`, `MODE_CALL_REDIRECT`}.
- Звонок окончен, когда mode вышел из этого множества и держится вне его ≥ 1 с. Неважно, `MODE_NORMAL` это или `MODE_IN_COMMUNICATION`.
- START RIDE во время звонка → сразу `PAUSED_BY_CALL`.
- Каждую смену mode пишем в лог.

**Пауза** (строго в этом порядке):
1. Остановить `AudioTrack` и `AudioRecord`.
2. `clearCommunicationDevice()`.
3. `setMode(MODE_NORMAL)`.
4. Отправить партнёру `STATE(PAUSED_BY_CALL)`.

Nearby-соединение не рвём, PING продолжается. FGS остаётся: `stopForeground()` не вызываем, уведомление обновляем через `notify()`.

**Возобновление:**
1. `setMode(MODE_IN_COMMUNICATION)`.
2. Сразу запустить потоки.
3. `setCommunicationDevice()`.
4. Ждать SCO, как в шаге 4 FR-1. Таймаут → `DEVICE_LOST` и повторы.
5. `STATE(READY)`.

Во время сотового звонка SCO для нас не поднимется — повторяем после звонка.

**Партнёр:** `RemoteAudio = IN_CALL` → «Rider in call» в уведомлении и логе. Его ducking не активируется.

### FR-12. Диагностика

**Лог:** JSONL, каждая строка `{wallMs, monoMs, phone, event, …}`.
- Кнопка **«Mark»** с текстовой меткой (номер теста) пишет маркер.
- Экспорт через Share после каждого теста.
- Логи двух телефонов сводим по `wallMs`.

На экране и в логе:

```text
Phone model, One UI                    Wi-Fi / BT enabled
Audio mode changes                     Communication device, getRoutedDevice()
SCO state (BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
A2DP playing state (BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
Requested sample rate, buffer sizes    Mic silenced (isClientSilenced)
VAD: noise floor, frame dBFS, gate     RTT (PING/PONG раз в 1 с)
seq gaps (ожидаем ≈0), late, dup       Inter-arrival p50/p95/max
Playout depth, underruns, overflow     In-flight, tx_dropped
TX/RX kbit/s, payloads/s               Talkspurts
Nearby quality: последнее onBandwidthChanged() + время от STATUS_OK до HIGH
Reconnect: причина, время детекта, время reconnect, счётчик
Audio focus: запросы, результаты, события
Чужие плееры: usage / contentType / state
Переходы трёх осей FR-9
```

`onBandwidthChanged()` приходит только при установке соединения и при **улучшении** качества. Ухудшение видно косвенно — по RTT, in-flight и underruns. Метод не абстрактный, его легко забыть переопределить.

### FR-13. UI (один экран)

```text
┌─────────────────────────────────┐
│ MotoTalk POC                    │
│ Link:   CONNECTED  Ivan's S23   │
│ Local:  READY  (EJEAS, SCO, WB) │
│ Remote: READY                   │
│ RTT 34 ms · in-flight 1 · HIGH  │
│                                 │
│ [Gate ◉ | Always ○]  ▁▃▅ -42dB  │
│ Threshold +N dB ────●────       │
│                                 │
│ [ START RIDE ]   [ Loopback ]   │
│ [ Record 10 s ] [ Duck test ]   │
│ [ Mark ]  [ Diagnostics ]       │
└─────────────────────────────────┘
```

Debug-настройки: `target` playout, лимит in-flight N, `T_dead`, pre-roll, `ConnectionType` (BALANCED / DISRUPTIVE).

---

## 5. Протокол POC

Заголовок пакета (big-endian, 13 байт):

```text
version      u8
type         u8    AUDIO | PING | PONG | HELLO | STATE | BYE
seq          u32
timestampMs  u32   монотонные часы отправителя (для AUDIO — время захвата)
flags        u8    bit0 = речь (решение VAD с hangover; pre-roll = 1)
payloadLen   u16
payload      bytes
```

- **seq:**
  - у AUDIO отдельный счётчик, растёт только на реально отправленных кадрах (кадры, подавленные гейтом или лимитом in-flight, `seq` не увеличивают), поэтому потери = пропуски `seq`;
  - у служебных сообщений свой общий счётчик.
- **Сброс:** при каждом `onConnectionResult(STATUS_OK)` отправитель начинает `seq` с 0, приёмник сбрасывает ожидаемый `seq` и playout-буфер. Новое соединение = новая сессия.
- **HELLO** (UTF-8 JSON): `deviceId`, имя, версия приложения, `sampleRate` (для диагностики), текущее `LocalAudio`. Первое сообщение с обеих сторон после соединения. AUDIO до получения HELLO партнёра игнорируется.
- **STATE**: новое значение `LocalAudio`, отправляется при каждом изменении.
- **PING**: payload = `timestampMs` отправителя. **PONG** возвращает `seq` и `timestampMs` исходного PING без изменений. `RTT = now − echo` (часы своего телефона).
- **BYE**: пользователь нажал Stop. Партнёр не считает это обрывом (`RemoteAudio = STOPPED`).

---

## 6. Стек и параметры

| Параметр | Значение |
|---|---|
| Язык / UI | Kotlin, Jetpack Compose |
| minSdk / targetSdk / compileSdk | **34 / 36 / 36**. minSdk 34 — оба телефона на Android 14+, ветки для API 31–33 не нужны. targetSdk 36 — чтобы в POC не включались изменения Android 17 (`ACCESS_LOCAL_NETWORK`, ограничения фонового аудио). Переход на 37 — отдельной задачей |
| Аудио | `AudioRecord` / `AudioTrack` (Java API); Oboe — только если упрёмся в задержку |
| Транспорт | `com.google.android.gms:play-services-nearby:19.3.0`. 19.4+ собраны Kotlin 2.3/2.4 и не компилируются Kotlin 2.1 (см. строку ниже). Переход на 19.5.1 — вместе с обновлением Android Studio |
| AGP / Kotlin / Gradle | **8.10.1 / 2.1.21 / 8.12**. AGP 8.10.x — потолок установленной Android Studio 2024.3.2 (Meerkat FD); Gradle 8.12 — просто выбранная версия (нужна ≥ 8.11.1, работает на JDK 17–23). После обновления Studio — AGP 9.x, Kotlin 2.4, Nearby 19.5.1 одним шагом |
| Сборка | Gradle KTS, один модуль, пакеты по §2 плана (`audio`, `transport`, `intercom`, `bluetooth`, `service`, `ui`) + `diag` |
| Код | В этой папке, git-репозиторий; applicationId `dev.mototalk.poc` (= Nearby `SERVICE_ID`) |

**Разрешения:**

| Вид | Разрешения |
|---|---|
| **Runtime** (до Loopback / START RIDE, см. FR-8) | `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `NEARBY_WIFI_DEVICES`, `POST_NOTIFICATIONS`. Четыре «устройства поблизости» — один системный диалог |
| **Install-time** | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `MODIFY_AUDIO_SETTINGS`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE`. Wi-Fi-разрешения — без `maxSdkVersion`: безвредно и нужно для `isWifiEnabled()` и предусловия `connectedDevice`. `ACCESS_NETWORK_STATE` — проверка «телефон подключён к Wi-Fi-сети» (§8.7) |
| **Не нужны** | `ACCESS_FINE_LOCATION` (Nearby требует только на API 29–31), `READ_PHONE_STATE` (звонок детектим по audio mode), `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_PHONE_CALL` (D13) |
| **Позже** | `ACCESS_LOCAL_NETWORK` — только при переходе на targetSdk 37 |

На `BLUETOOTH_SCAN` флаг `usesPermissionFlags="neverForLocation"` не ставим.

---

## 7. Чек-лист замеров Phase 0

M1–M13 — loopback, один телефон + шлем, всё внутри `RideService`. M13–M14 — во время P2P.

| # | Что проверяем | Как фиксируем |
|---|---|---|
| M1 | EJEAS виден как communication device; `setCommunicationDevice()` → callback с SCO | лог, `getRoutedDevice()` |
| M2 | **Кодек SCO: WB (mSBC) или NB (CVSD)** | «Record 10 s», говорим / шипим «с-с-с», спектрограмма в Audacity: энергия выше ~4 kHz → WB, нет → NB. Подсказка: `adb logcat -s HeadsetStateMachine` → `hasWbsEnabled=…` (лог AOSP, на Samsung может отсутствовать). Значение из `AudioRecord` кодек **не** показывает |
| M3 | Full-duplex: говорю и слышу себя одновременно | на слух, шлем на голове |
| M4 | Задержка loopback | (1) оценка по буферам — нижняя граница, без BT-линка и DSP шлема; (2) акустически: второй телефон / ноутбук как диктофон у микрофона и динамика шлема, 5 хлопков, интервал в Audacity, медиана |
| M5 | Время A2DP → SCO и обратно | Старт/стоп Loopback при играющем Spotify, 10 циклов. Старт: t0 `setCommunicationDevice()`; t1 callback SCO; t2 `ACTION_AUDIO_STATE_CHANGED = CONNECTED`; t3 первый ненулевой кадр `AudioRecord`; t4 A2DP `NOT_PLAYING`. Стоп: t0′ `clearCommunicationDevice()`; t2′ SCO `DISCONNECTED`; t4′ A2DP `PLAYING`. Медиана и максимум t0→t3 и t0′→t4′ |
| M6 | Spotify при открытом SCO | Ожидание (AOSP): играет через SCO, моно. На слух + `adb shell dumpsys media.audio_policy` |
| M7 | Spotify при «Duck test»: duck или pause? | на слух + свои события фокуса + состояния чужих плееров + `adb shell dumpsys audio` («ducked players piids») |
| M8 | **Google Maps при открытом SCO** | Матрица 2×2 настроек Maps: «Play voice during phone calls» × «Play voice over Bluetooth». Для каждой: (a) подсказка слышна без речи партнёра; (b) обрывается ли она при «Duck test»; (c) слышен ли партнёр во время подсказки. Лучшую комбинацию выставляем перед тестами §8 и пишем в лог |
| M9 | Звонок с третьего телефона: принять / отклонить / не отвечать → звук возвращается сам? | лог смен mode + на слух. По руководству шлем сам принимает звонок, если 5 с ничего не нажимать — проверить, как это сочетается с паузой |
| M10 | Выключить / включить шлем → маршрут восстанавливается сам? | лог, время |
| M11 | Экран заблокирован 30 мин → всё работает, микрофон не заглушён | лог, `isClientSilenced` |
| M12 | Кнопки EJEAS | Ожидание по руководству: Phone коротко — play/pause (уйдёт в Spotify как последний медиаплеер, не в MotoTalk); Phone 3 с — повтор последнего номера (настоящий звонок → FR-11); Phone 5 с — сопряжение; Vol± — громкость; Vol± 2 с — трек. Во время сессии кнопка может работать как HFP «ответ/сброс» и рвать SCO. Логируем, что доходит до приложения и что рвёт SCO |
| M13 | Эхо (Phase 2) | Райдеры не слышат друг друга напрямую (разные комнаты или ≥ 30 м). B молчит, A говорит и слушает задержанное эхо. Счётчик «гейт B открылся, пока играет голос A». Подсказка: `hasNrecEnabled` в той же строке logcat, что M2 |
| M14 | Треск / пропадания в шлеме, пока идёт Nearby discovery / advertising (Phase 1–2) | на слух + лог (`SEARCHING` / `RECONNECTING`) |

### 7.1. Замер задержки mouth-to-ear (A → B)

1. Рекордер — третье устройство (ноутбук с Audacity или телефон), запись 48 kHz.
2. Микрофон рекордера и динамик шлема B накрыты вместе (коробка / полотенце). Микрофон шлема A — снаружи, в 1–2 м, чтобы A не слышал динамик B (иначе петля A→B→A).
3. На B гейт с максимальным порогом: B ничего не передаёт. На A — «Передавать всегда».
4. Щелчок (ручкой по столу) у микрофона A. В записи два импульса: прямой и из динамика B. Δt = задержка A→B (поправка на воздух ~3 мс/м).
5. 10 щелчков, медиана и максимум. Затем то же для B→A. Один прогон с A в режиме «Гейт» — разница даёт вклад pre-roll.

---

## 8. Критерии приёмки POC

Приёмка = тесты Stage A–B (пешком, до 50 м). Stage C на мотоциклах — после приёмки, не критерий POC.

1. **Сессия ≥ 30 мин**, оба телефона заблокированы в карманах, ни одного касания. В логе: число авто-reconnect и суммарное время без связи (порога нет).
2. **Reconnect:** 3 раза один райдер уходит, пока в логе / уведомлении не появится обрыв, и возвращается на ~20 м. Экраны заблокированы. Пройдено = голос вернулся без касаний телефона; время детекта и reconnect каждого раза записано. Если с заблокированным экраном reconnect не работает — это находка POC и вход для решения о Wi-Fi Direct.
3. **Музыка:**
   - Spotify играет (через SCO — качество записываем).
   - Во время речи партнёра музыка тише или на паузе — записываем, что именно. Пауза — находка для MVP, не провал POC.
   - После конца речи партнёра музыка **возвращается сама** — обязательно.
   - Подсказка Google Maps без наложения на речь партнёра слышна целиком; что происходит при наложении — записываем.
4. **Звонок** с третьего телефона на телефон A: (а) принять, говорить ~30 с, положить; (б) отклонить; (в) не отвечать. Каждый раз у B в логе и уведомлении есть «Rider in call» (проверяем после теста). После звонка голос в обе стороны вернулся без касаний; время — в логе.
5. **Задержка** A→B и B→A измерена по §7.1 и записана (порога нет).
6. **Дальность:** открытая площадка, точки 10 / 30 / 50 м, по 3 мин на точке, телефоны в карманах. Записываем quality, underruns, `tx_dropped`, межпакетный интервал, разборчивость (да/нет). Порога нет; результат на 50 м записан.
7. **Без сети:**
   - Во всех P2P-тестах телефоны **не подключены ни к одной Wi-Fi-сети**; сами Wi-Fi и Bluetooth включены. Иначе Nearby может пустить трафик через общий роутер (WIFI_LAN), и тест не проверит P2P.
   - Один прогон п.1 — ещё и с выключенными мобильными данными. Музыка — скачанный плейлист Spotify (нужен Premium) или локальный плеер. Google Maps — офлайн-карта, навигация запущена до старта сессии.
8. Для каждого теста есть diagnostics-логи **с обоих телефонов** с метками «Mark».
9. Заполнен чек-лист §7 — по нему владелец решает режим SCO для MVP (D4).

---

## 9. Допущения (можно оспорить)

- Стартовые значения, все настраиваются: 16 kHz / 20 мс кадры / `target` 3 кадра / in-flight 10 / `T_dead` 5 с / pre-roll 100 мс.
- **Wideband — гипотеза.** Вендор не публикует профиль HFP, поддержку mSBC и A2DP-кодеки V6 Pro+. Известно только: Bluetooth 5.1, 2 телефона одновременно, CVC/DSP-шумоподавление, «HD-Voice». Если канал окажется NB (CVSD) — POC идёт дальше, в MVP учитываем при выборе кодека.
- **Эхо** подавляет CVC шлема: вендор заявляет подавление эха, но это не проверено. Платформенный AEC на пути SCO не гарантирован: зависит от NREC гарнитуры и HAL производителя, `AcousticEchoCanceler.isAvailable()` об этом не говорит. Остаточное эхо может открывать гейт партнёра. Если эхо есть — находка для Phase 4.
- **LE Audio недоступен:** EJEAS V6 Pro+ — Bluetooth 5.1, а LE Audio требует 5.2+. Микрофон шлема доступен только через HFP/SCO, поэтому компромисс A2DP/SCO с этим шлемом не обойти ни в POC, ни в MVP. LE Audio-гарнитура — отдельное исследование после POC.
- **Nearby `ConnectionType` = `BALANCED`** (значение по умолчанию), задаётся явно с двух сторон: `AdvertisingOptions.Builder.setConnectionType()` и `ConnectionOptions.Builder.setConnectionType()`. `DISRUPTIVE` — только debug-переключатель для A/B-теста, если quality не доходит до HIGH. Google не рекомендует disruptive, когда нужен интернет (онлайн-Spotify / Maps) или соединение живёт в фоне. При тесте DISRUPTIVE проверяем, не рвётся ли шлем и работает ли мобильный интернет.
- Голосовых оповещений в POC нет; состояние видно в уведомлении.
- Пейринг — только 4-значный код Nearby + Accept, без своего экрана с кодом.
- Отключение battery optimization не требуем. На One UI 6+ FGS приложений с targetSdk ≥ 34 гарантированно работают, а для микрофона это исключение всё равно не помогает.

---

## 10. Риски POC

| Риск | Последствие | Что делаем |
|---|---|---|
| Музыка в SCO-качестве. По AOSP: в `MODE_IN_COMMUNICATION` media и навигация уходят в SCO (моно, полоса звонка), A2DP исключён на всю сессию. Платформа музыку не глушит (глушит только для звонков через Telecom), но Samsung-специфика не задокументирована | Музыка звучит плохо; при откате режима в `MODE_NORMAL` с выбранным SCO может пропасть | Ловим на M5–M8 в Phase 0, до P2P → стоп-точка §11 |
| Nearby остаётся на Bluetooth (quality MEDIUM / LOW) или долго переходит на Wi-Fi; BT-контроллер общий с eSCO шлема (механизм не задокументирован) | Голос идёт по BT: потери, рост задержки, треск в шлеме | Проверка BT/Wi-Fi перед START (FR-8); логируем quality и время до HIGH. Сравниваем время до HIGH без SCO (этап M2 §11) и с SCO (M3+). Если HIGH не наступает — вручную DISRUPTIVE (debug); крайний вариант — Wi-Fi Direct + UDP за `PeerTransport` (вне POC) |
| Сосуществование Wi-Fi и BT в 2.4 GHz | Рост джиттера и задержки, возможен треск в SCO. Потерь по `seq` почти не будет — доставка надёжная | Логируем quality, межпакетный интервал, underruns, `tx_dropped`; на слух сравниваем чистоту SCO в Loopback (без Nearby) и в P2P |
| Надёжная доставка Nearby: `sendPayload()` лишь ставит в очередь | При нехватке канала очередь и задержка растут вплоть до разрыва; сброс на приёмнике очередь не уменьшает | Лимит in-flight (FR-4), сброс опоздавших кадров на приёмнике (FR-5), счётчики в диагностике |
| Ограничения while-in-use для microphone FGS (Android 14+) | `SecurityException` при старте из фона; любой повторный `startForeground()` из фона заново проверяет состояние — микрофон замолкает | Сервис стартует только из видимой Activity и живёт до Stop. Пауза на звонок и reconnect — только stop/start потоков внутри работающего FGS. Проверяем в M9 / M11 |
| Кнопка шлема «сброс вызова» рвёт SCO (виртуальный звонок) | Звук пропадает, шлем остаётся подключён | Автовосстановление SCO (FR-1), M12, предупредить райдеров |
| Эхо через шлем | Партнёр слышит себя, ложные срабатывания гейта | M13; при необходимости — AEC в Phase 4 |
| Дальность 50 м с телефонами в карманах не подтверждена | Обрывы на дистанции | Критерий §8.6; если нестабильно — для Stage C телефон в держателе на руле или в сумке на баке (до шлема ≤ 10 м) |
| Телефоны в одной Wi-Fi-сети | Nearby идёт через роутер (WIFI_LAN) → ложный успех P2P | Критерий §8.7 |

---

## 11. Порядок работ

```text
M0  Каркас проекта, разрешения, экран диагностики, лог JSONL,
    RideService (FGS microphone|connectedDevice) + уведомление со Stop
 ↓
M1  Loopback внутри RideService + FR-1 + локальная обработка звонка (FR-11 без партнёра)
    + Record 10 s + Duck test → чек-лист §7 M1–M12
    ← СТОП-ТОЧКА
 ↓
M2  Nearby: discovery, pairing, HELLO, PING/PONG, детект обрыва, reconnect
 ↓
M3  PCM full-duplex + playout-буфер + VAD-гейт + лимит in-flight
 ↓
M4  Ducking по флагу речи партнёра, STATE / статус партнёра, M13–M14
 ↓
M5  Тесты Stage A–B (пешком, до 50 м) = приёмка POC (§8)
    Stage C (мотоциклы) — после приёмки
```

**Стоп-точка после M1.** Вопрос к владельцу: **приемлема ли музыка в SCO-качестве** (M6–M8) и сколько стоит переключение A2DP↔SCO (M5)?
- Если да — идём в P2P с SCO на всю сессию.
- Если нет — до P2P решаем режим для MVP, например SCO только по кнопке шлема, если кнопки доступны (M12). VAD по микрофону шлема без открытого SCO невозможен.

### 11.1. Логистика тестов

- **Loopback:** шлем на голове или динамики ≥ 30 см от микрофона; первый запуск — громкость на минимуме.
- **Stage A (помещение):** райдеры в разных комнатах, чтобы не слышать друг друга напрямую; включённые шлемы рядом не кладём.
- **Встроенный интерком EJEAS** (кнопки MOTOR / B–E) выключен, шлемы не спарены между собой. Иначе голос может идти шлем→шлем мимо приложения.
- **Каждый шлем спарен только со своим телефоном.** Шлем держит 2 телефона одновременно, поэтому на других сопряжённых устройствах выключаем Bluetooth или удаляем шлем из списка. Если не помогает — Clear pairing (Phone + B одновременно ~2 с; это сброс к заводским настройкам) и сопрячь заново. Проверять перед каждым тестом.
- **Дополнительные устройства:** третий телефон для тестов звонка; рекордер (ноутбук или третий телефон) для §7.1.
- **Сети:** во время P2P-тестов телефоны не подключены ни к какой Wi-Fi-сети (§8.7).
- **Модели и версии One UI** обоих телефонов записываем в лог.

---

## 12. Что меняется в основном плане

- **Reconnect, foreground service и ducking** переезжают в POC (были в Phase 5 / §10–11 и шагах 7–9 roadmap).
- **VAD** уточнён: гейт поверх постоянно открытого SCO. Без открытого SCO микрофон шлема недоступен, поэтому «включение микрофона по голосу» невозможно.
- **Музыка во время сессии идёт через SCO** (моно) — это ожидаемое поведение платформы, а не баг. Главный вопрос стоп-точки.
- **Telecom не используем** (D13), хотя Google рекомендует его для VoIP.
- **LE Audio** с EJEAS V6 Pro+ недоступен (Bluetooth 5.1).
- **Цель < 200 мс** — критерий этапа Opus, не POC.
- Добавлены **дальность до 50 м**, **поведение при звонке** и **тесты без сети**.
- Приёмка POC — пешком (Stage A–B); мотоциклы (Stage C) — после.

---

## 13. Источники (проверено 2026-10-06)

**Android**
- AudioManager: https://developer.android.com/reference/android/media/AudioManager
- AudioRecord: https://developer.android.com/reference/android/media/AudioRecord
- Audio focus: https://developer.android.com/media/optimize/audio-focus
- Communication device / SCO (BLE audio guide): https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager
- Sharing audio input: https://developer.android.com/media/platform/sharing-audio-input
- FGS types: https://developer.android.com/develop/background-work/services/fgs/service-types
- FGS background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 15 behavior changes: https://developer.android.com/about/versions/15/behavior-changes-15
- Telecom VoIP API updates: https://developer.android.com/develop/connectivity/telecom/voip-app/api-updates

**AOSP (android14-release)**
- `AudioService`, `AudioDeviceBroker`, `MediaFocusControl`, `PlaybackActivityMonitor`, `BtHelper`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/audio/
- `Engine.cpp`, `AudioPolicyManager.cpp`, `policy.h`: https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/services/audiopolicy/
- `HeadsetStateMachine`, `HeadsetService`: https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release/android/app/src/com/android/bluetooth/hfp/

**Nearby Connections**
- Overview: https://developers.google.com/nearby/connections/overview
- Get started (manifest и разрешения): https://developers.google.com/nearby/connections/android/get-started
- Discover devices: https://developers.google.com/nearby/connections/android/discover-devices
- Manage connections: https://developers.google.com/nearby/connections/android/manage-connections
- Exchange data: https://developers.google.com/nearby/connections/android/exchange-data
- Strategies: https://developers.google.com/nearby/connections/strategies
- API reference (`ConnectionsClient`, `ConnectionLifecycleCallback`, `BandwidthInfo.Quality`, `ConnectionType`): https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/package-summary
- Открытая реализация: https://github.com/google/nearby

**Железо и приложения**
- EJEAS V6 Pro+: https://www.ejeas.com/products/v6-pro
- Руководство EJEAS: https://www.ejeas.com/user-manual/
- Samsung app management (FGS на One UI): https://developer.samsung.com/mobile/app-management.html
- Google Maps — голос во время звонков: https://support.google.com/maps/answer/3273406
