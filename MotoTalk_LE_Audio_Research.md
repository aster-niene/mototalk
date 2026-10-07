# Микрофон шлема и хорошая музыка одновременно: что есть на октябрь 2026

## 1. Короткий ответ

**С EJEAS V6 Pro+ так не получится.** Сейчас на рынке нет мотогарнитуры, которая отдаёт микрофон шлема в приложение на телефоне и при этом играет музыку в хорошем качестве.

**Номер версии Bluetooth тут ничего не решает.** «5.2», «5.3», «5.4» и даже «Bluetooth 6» говорят только о версии радиочасти. Пример: Sena APEX рекламируют как «Bluetooth 6», но в спецификации и инструкции у неё только HFP, A2DP и AVRCP [5][6].

**Работает только LE Audio** (BT 5.2+, кодек LC3, BAP unicast). У гарнитуры должна быть прямо заявлена поддержка LE Audio, и её не нашлось ни у одной мотогарнитуры:
- Sena, Cardo, EJEAS, Interphone, Midland, UCLEAR, Asmax, Lexin, FreedConn, Nolan: у всех связь с телефоном идёт только по Classic (HFP для звонков и микрофона, A2DP для музыки) [1–23].
- Базу Bluetooth SIG проверить не удалось: сайт не открывается без JavaScript. Поэтому LE Audio «в железе без объявления» исключить нельзя, но ни один продукт её не включает.

**Почему на Classic это невозможно.** Как только Android открывает голосовой канал SCO к любому устройству, он выключает A2DP для всех устройств (в коде AOSP это `A2dpSuspended=true`) [50]. Поэтому музыка и проваливается в 3/5.

**Что делать сейчас:**
1. **Новую гарнитуру ради LE Audio не покупать.** Таких нет, включая модели «BT 5.4» и «BT 6».
2. **Проверить, умеют ли оба телефона LE Audio unicast.** По S24 FE официального подтверждения нет (не проверено). Проверяется командами:
   - `adb shell getprop bluetooth.profile.bap.unicast.client.enabled`
   - `adb shell getprop bluetooth.profile.gmap.enabled`
   - `adb shell getprop bluetooth.core.le_audio.codec_extension_aidl.enabled`

   [39][44][45]
3. **Для проверки телефонной части купить LE Audio наушники:** Pixel Buds Pro 2, Sony WF-1000XM5 или Sony LinkBuds Fit [54][55][56]. На них можно проверить связку «микрофон гарнитуры + Spotify» в MotoTalk без шлема.
4. **Бесплатно сегодня:** включить на двух V6 Pro+ интерком гарнитура-гарнитура и одновременно Spotify. У EJEAS режим «разговор + музыка одновременно» заявлен для Q8, MS20 и X10 Plus. Для V6 Pro+ он не подтверждён, нужно просто попробовать [15][16][63]. MotoTalk в этом варианте не участвует, дальность ограничена радиосвязью гарнитур (~800 м) [14].

## 2. Проверенные гарнитуры

Гарнитур с LE Audio для связи с телефоном не найдено ни одной. В таблице то, что проверено: везде Classic.

| Модель | BT | Профили к телефону | LE Audio / LC3 / Auracast | Уверенность |
|---|---|---|---|---|
| Sena 60S / 60S EVO (май 2026, ~473–557 €) | 5.3 | HSP/HFP/A2DP/AVRCP | нет | подтверждено [1][2][3] |
| Sena APEX / APEX PLUS (авг 2026), «Bluetooth 6» | 6 (только радиочасть) | HFP/A2DP/AVRCP | нет | подтверждено [5][6] |
| Sena 60X, Spider X Slim ($299), шлем Specter | 5.3 | HSP/HFP/A2DP/AVRCP | нет | подтверждено [7][8][9] |
| Cardo Packtalk Edge ($439.95) / Pro / Neo, Schuberth SC-Edge | 5.2 | HFP/A2DP/AVRCP | нет; прошивка Mesh Boost (авг 2026) звук по Bluetooth не меняет | подтверждено [10][11][12] |
| Cardo PACKTALK-S / 4X-S (Shoei Gen 3) | не указан | — | не упомянуто | подтверждено [13] |
| **EJEAS V6 Pro+** ($39.99) | 5.1 | Classic | нет; анонсов LE Audio у EJEAS нет | подтверждено [14][15] |
| EJEAS 2026: X10/X10 Plus, K1, MS8, MS20, Q8 | 5.1 | Classic | нет; у X10 Plus «интерком + музыка одновременно», но это смешивание внутри гарнитуры, микрофон в телефон не попадает | подтверждено [15][16] |
| Interphone ERA 1X | 5.3 | — | не упомянуто | вероятно [17] |
| Midland BTR1 (MBE 2026) | — | — | не упомянуто | вероятно [18] |
| UCLEAR Motion | 5.0 | — | нет | вероятно [19] |
| Asmax F1 Pro Max, Lexin, FreedConn F1 Plus V2 (5.4), Nolan B902 | 4.1–5.4 | — | нет | вероятно [20–23] |

Не проверено:
- Fodsports «BT 5.4» на чипах Qualcomm: LE Audio не заявлена, умеет ли её железо, неизвестно.
- Ветка форума, где говорится, что «у Sena в серии 50 нет железа под LE Audio, Auracast не в приоритете». Её не удалось открыть, видно только краткое содержание из поиска [4].
- LC3 в мотоинтеркомах встречается только в любительских проектах, и там это кодек связи между гарнитурами, а не с телефоном [24].

## 3. Что даст Android/Samsung, если гарнитура будет с LE Audio

1. **Музыка не останавливается, микрофон открыт, кнопки и микрофон телефона не нужны.** Подтверждено кодом AOSP, на Samsung не проверено.
   - Чтобы записывать с LE Audio микрофона, достаточно AudioRecord с `setPreferredDevice(TYPE_BLE_HEADSET)`. Режим звонка не нужен [32][33].
2. **Качество музыки при открытом микрофоне: LC3 32 kHz, а не 48 kHz.** Подтверждено для AOSP; что выбирает Samsung, не проверено.
   - Android держит один режим на всю гарнитуру сразу. Когда микрофон открыт, побеждает двусторонний режим, и музыка идёт в нём же [26].
   - В таблицах AOSP первым идёт LC3 32 kHz, 10 ms, 80 octets (~64 kbps на канал): стерео, если у гарнитуры два канала, и моно 32 kHz на микрофон. Запасной вариант 16 kHz [28][29].
   - Обычный режим музыки в LE Audio: 48 kHz [28].
   - Режим 32 kHz обязателен для любой гарнитуры с ролью TMAP Call Terminal [30]. Google тоже пишет, что с микрофоном вход и выход «can reach 32 kHz» [31].
   - Что это значит на слух: полоса звука около 16 kHz против ~8 kHz у нынешнего mSBC, стерео вместо моно. Это заметно лучше, чем 3/5, но хуже A2DP. Моя оценка, что будет около 4/5, — это вывод, а не замер.
3. **Короткий провал звука в момент, когда микрофон открывается или закрывается.** Вероятно; это вывод из кода (поток останавливают и пересобирают), не замерено [26].
4. **48 kHz музыка вместе с микрофоном (GMAP, контекст GAME) на Samsung, скорее всего, недоступна.**
   - Такой режим есть только в AOSP Android 16, и только если его включил производитель телефона (sysprop + HAL), а гарнитура поддерживает GMAP [36][37][38][39].
   - SoundGuys (июль 2026, S24 Ultra) пишут, что GMAP пока не работает ни на одном их устройстве, включая Samsung. При этом звук игры с включённым голосовым чатом по LE Audio «stayed intact». Это один субъективный отзыв [43].
5. **Приложение не может само выбрать кодек или его параметры.** Подтверждено.
   - `setCodecConfigPreference` и `getCodecStatus` доступны только системным приложениям (@SystemApi, BLUETOOTH_PRIVILEGED) [41].
   - Повлиять можно только косвенно: через usage у AudioTrack и source у AudioRecord [25].
6. **S24 FE и LE Audio unicast: не проверено.**
   - Характеристики: BT 5.3, Exynos 2400e [44].
   - На странице Samsung про Auracast S24 FE нет, есть только S24/S24+/Ultra [45][46].
   - Настоящие таблицы кодеков у Samsung закрытые: Bluetooth-модуль Mainline с Android 13 необязателен для производителей [42].
   - В 2024 году пользователи Exynos S24 жаловались на плохую музыку по LE Audio со слуховыми аппаратами. Это отзыв с форума, не проверено [58].
7. **LE Audio на гарнитуре, которая умеет и Classic, и LE, обычно надо включить вручную.** Вероятно.
   - На Samsung это переключатель «LE audio» в настройках конкретного устройства [43][49].
   - Есть сообщение, что One UI 8 убрал этот переключатель для Galaxy Buds3 Pro. Его видно только в выдаче поиска, сама страница отдала 403, не проверено [57].
   - В AOSP без переключателя LE Audio включается только для устройств из allowlist [47][48].
8. **В режиме MODE_IN_COMMUNICATION Spotify подмешивается в голосовой поток, а не ставится на паузу.** Подтверждено для AOSP.
   - Пауза будет, только если приложение забирает audio focus [35].
   - Если приложение играет звук с USAGE_VOICE_COMMUNICATION, стек считает это VoIP-звонком и принудительно ставит режим CONVERSATIONAL [26][34].

## 4. Альтернативы

- **Интерком гарнитура-гарнитура с музыкой одновременно** (Sena 30K, Cardo Packtalk, EJEAS Q8/MS20/X10 Plus): работает уже сейчас, но мимо телефона и MotoTalk, дальность только радиосвязи. Для V6 Pro+ не подтверждено [60][61][62][63][16].
- **HFP 1.9 LC3-SWB:** это по-прежнему SCO, значит A2DP выключен. На Galaxy работает только с Galaxy Buds, мотогарнитур с ним нет. Тупик [50][51][52][53].
- **aptX Voice / Snapdragon Sound:** только для Qualcomm, а S24 FE на Exynos. Тупик [64][65][66].
- **Второй Bluetooth-микрофон по HFP:** SCO выключает A2DP для всех устройств сразу. Не работает [50][67].
- **Радиопетличка 2.4 GHz в шлеме (например, DJI Mic Mini) с USB-C приёмником в телефоне:** SCO не открывается, A2DP остаётся. Есть риск, что приёмник заберёт себе вывод Spotify. Ветер и шум в шлеме не проверены [35][50][67].
- **LE Audio наушники под шлемом:** настоящий двусторонний LC3. Годится как тестовый вариант, удобство и ветер под шлемом не проверены [32][54][55].
- **SDK от Sena или Cardo:** публичного SDK не найдено, только их обычные приложения [68].

## 5. Что изменить в MotoTalk

**Как сейчас:**
- `AudioSession.kt:210` ставит `MODE_IN_COMMUNICATION`.
- `AudioSession.kt:225-236` выбирает сначала `TYPE_BLE_HEADSET`, потом `TYPE_BLUETOOTH_SCO`, и вызывает `setCommunicationDevice`.
- `VoiceIo.kt:215` воспроизводит с `USAGE_VOICE_COMMUNICATION`, `VoiceIo.kt:242` записывает с `AudioSource.VOICE_COMMUNICATION`, 16 kHz.

С LE Audio гарнитурой этот путь, скорее всего, заработает, но в режиме CONVERSATIONAL (как VoIP-звонок, 32 kHz по таблицам AOSP) [26][34].

**Предлагаю отдельный «LE-режим», а Classic/SCO оставить запасным:**
1. **Определение.**
   - `BluetoothAdapter.isLeAudioSupported() == FEATURE_SUPPORTED` [31].
   - В `am.getDevices(GET_DEVICES_INPUTS)` и `GET_DEVICES_OUTPUTS` есть устройство с типом `AudioDeviceInfo.TYPE_BLE_HEADSET`.
2. **Запись.**
   - `AudioRecord`, `AudioSource.MIC` (стек называет это контекстом LIVE), PCM16, 32 kHz mono, `setPreferredDevice(BLE input)`, как в гайде Google [32].
   - Без `MODE_IN_COMMUNICATION` и без `setCommunicationDevice`.
   - Для своего кодека понижать частоту до 16 kHz внутри приложения.
3. **Голос собеседника.**
   - `AudioTrack` с `USAGE_GAME` и `setPreferredDevice(BLE output)`.
   - Никогда не использовать `USAGE_VOICE_COMMUNICATION`, иначе стек принудительно включит CONVERSATIONAL.
   - В Android 16 AOSP режим GAME сохраняется, когда открывается микрофон [27][25]. На Samsung не проверено.
4. **Микрофон держать открытым всю поездку.** Тишину отсекает наш VadGate, а сам AudioRecord не закрывать. Иначе при каждом открытии и закрытии поток пересобирается и звук проваливается (это вывод из [26]). Google сам советует заранее «взводить» микрофон [31].
5. **Не забирать audio focus**, иначе Spotify встанет на паузу [35].
6. **Защита от микрофона телефона.**
   - `VoiceIo.kt:154` сейчас принимает только `TYPE_BLUETOOTH_SCO`, туда нужно добавить `TYPE_BLE_HEADSET`.
   - Если `routedDevice` равен `TYPE_BUILTIN_MIC`, писать ошибку и не передавать звук.
7. **Что логировать.**
   - Аудио-маршрут:
     - `AudioRecord.routedDevice` и `AudioTrack.routedDevice` (тип и имя);
     - `sampleRates`, `channelCounts`, `encodings` у BLE-устройства;
     - `am.mode`, `am.communicationDevice`.
   - Кто сейчас играет и пишет:
     - `am.activePlaybackConfigurations` (играет ли Spotify и с каким usage);
     - `am.activeRecordingConfigurations`;
     - события `onRoutingChanged` и `AudioDeviceCallback`.
   - Время открытия микрофона: чтобы сверить его с тем, когда слышен провал звука.
   - Состояние Bluetooth и стека:
     - `isLeAudioSupported()`;
     - три getprop из раздела 1;
     - снимок `adb shell dumpsys bluetooth_manager`: строка LE Audio `Configuration:` и кодеки динамика и микрофона. Печатает ли их сборка Samsung, не проверено [43].

**План проверки на железе:**
1. На обоих телефонах выполнить три getprop. Если `bap.unicast.client.enabled` не `true`, LE Audio на этом телефоне не будет, дальше не идём.
2. Подключить LE Audio наушники и включить переключатель «LE audio» в настройках устройства (у Sony ещё и в их приложении) [43][55]. Проверить, что в диагностике MotoTalk есть `BLE_HEADSET` и на вход, и на выход.
3. Включить Spotify, запустить LE-режим. Проверить:
   - что музыка не останавливается и сколько длится провал;
   - что `routedDevice` равен `BLE_HEADSET`;
   - что показывает dumpsys;
   - как звучит музыка по шкале 1–5 (для сравнения: A2DP 5/5, SCO 3/5).
4. Сравнить два набора настроек: (MIC + USAGE_GAME, без mode) и (VOICE_COMMUNICATION + USAGE_VOICE_COMMUNICATION + MODE_IN_COMMUNICATION).
5. Проверить связку из конца в конец на двух телефонах, потом в шлеме на ходу (ветер, двигатель).
6. Параллельно: два V6 Pro+ в режиме интеркома плюс Spotify, чтобы проверить музыку во время разговора без приложения.

## Источники
1. https://www.sena.com/product/60s/
2. https://www.wrs.it/en/intercoms/500809-sena-60s-evo-bluetooth-intercom-wave-bluetooth-helmet-communication.html
3. https://www.sena.com/stories/news/
4. https://www.gl1800riders.com/threads/auracast-sena-possible-answer-below.491382/ (не открылся)
5. https://www.sena.com/en-us/product/apex-series/
6. https://firmware.sena.com/senabluetoothmanager/UserGuide_APEX_Series_1.0.0_en_260810.pdf
7. https://www.sena.com/product/60x/
8. https://ultimatemotorcycling.com/2026/09/07/sena-spider-x-slim-mesh-intercom-adds-bose-audio-299/
9. https://www.visordown.com/news/sena-specter-bluetooth-connected-modular-motorcycle-helmet-announced
10. https://cardosystems.com/products/packtalk-edge
11. https://powersportsbusiness.com/top-stories/2026/08/27/new-cardo-update-expands-group-connectivity-for-packtalk-users/
12. https://www.moto.it/accessori/cardo-e-schuberth-collaborano-per-lanciare-il-sistema-di-comunicazione-sc-edge-2025.html
13. https://powersportsbusiness.com/news/distributors-aftermarket/2026/04/15/cardo-launches-packtalk-s-and-4x-s-integrated-comm-systems-for-shoei-gen-3-helmets/
14. https://www.ejeas.com/products/v6-pro
15. https://www.ejeas.com/motorcycle/
16. https://www.ejeas.com/products/x10-plus
17. https://www.sportsbikeshop.co.uk/motorcycle_parts/content_prod/2723612
18. https://www.motorbox.com/moto/magazine-moto/midland-20-anni-di-interfoni-moto-novita-a-mbe-2026
19. https://www.revzilla.com/motorcycle/uclear-motion-6-single-pack
20. https://www.kaufland.de/product/544044954/
21. https://pacifiko.com/compras-en-linea/lexin-meshcom-motorcycle-helmet-bluetooth-headset-24-riders-mesh-communication-system-dual-chips-with-audio-multitasking-music-sharing-1150mah-battery-fm-radio-noise-reduction-waterproof-dual-pack-estilo-dual-pack&pid=YjllZWIxMD
22. https://pood.datafox.ee/audio-ja-video/korvaklapid-ja-mikrofonid/juhtmevabad-korvaklapid/freedconn-f1-plus-v2-eu-bt-5-4-edr-motorcycle
23. https://www.louis-moto.co.uk/en/nolan-n-com-b902l-r-20014237
24. https://github.com/JanWelker/bike_comm
25. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/le_audio/le_audio_utils.cc
26. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/le_audio/client.cc
27. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android16-release/system/bta/le_audio/client.cc
28. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/le_audio/audio_set_scenarios.json
29. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/le_audio/audio_set_configurations.json
30. https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/5690-TMAP-html5/out/en/index-en.html
31. https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview
32. https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording
33. https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager
34. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/le_audio/LeAudioService.java
35. https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/enginedefault/src/Engine.cpp
36. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/le_audio/device_groups.cc
37. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/gmap/gmap_client.cc
38. https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/bluetooth/audio/aidl/android/hardware/bluetooth/audio/ConfigurationFlags.aidl
39. https://android.googlesource.com/platform/system/libsysprop/+/refs/heads/main/srcs/android/sysprop/BluetoothProperties.sysprop
40. https://docs.zephyrproject.org/apidoc/latest/group__bt__gmap__lc3__preset.html
41. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothLeAudio.java
42. https://source.android.com/docs/core/ota/modular-system/bluetooth
43. https://www.soundguys.com/sony-wh-1000xm6-gaming-update-hands-on-159471/
44. https://www.gsmarena.com/samsung_galaxy_s24_fe-13262.php
45. https://www.samsung.com/au/support/mobile-devices/using-auracast/
46. https://blog.google/products/android/le-audio-auracast-support/
47. https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values/strings.xml
48. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/btservice/PhonePolicy.java
49. https://r1.community.samsung.com/t5/support/le-audio-lc3-one-ui-6-1/m-p/29134321/highlight/true
50. https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/audio/AudioDeviceBroker.java
51. https://www.bluetooth.com/specifications/specs/hands-free-profile/
52. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/stack/btm/btm_sco_hfp_hal.cc
53. https://news.samsung.com/uk/galaxy-buds4-series-elevates-call-clarity-with-hd-voice
54. https://support.google.com/googlepixelbuds/answer/7544332?hl=en
55. https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1001106279.html
56. https://helpguide.sony.net/mdr/2975/v1/en/contents/TP1001614209.html
57. https://eu.community.samsung.com/t5/galaxy-s25-series/unable-to-stream-audio-using-lc3-le-audio-codec-with-samsung/td-p/13298223 (403, видно только в выдаче поиска)
58. https://forum.hearingtracker.com/t/lc3-codec-and-le-audio/87906?page=3
59. https://android.googlesource.com/platform/frameworks/av/+/dfd9c5cc0c4a56e9cc078d9b23319e875f64d50a
60. https://www.sena.com/?p=7304
61. https://docs.kurviger.com/app/headsetnavigationsena
62. https://www.louis.ie/en/cardo-packtalk-bold-communication-system-10038480
63. https://www.masterfoto.lv/en/intercoms/13359-ejeas-ms20-motorcycle-intercom.html
64. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/ag/bta_ag_swb_aptx.cc
65. https://audioxpress.com/news/qualcomm-announces-snapdragon-sound-s5-and-s3-gen-2-chipsets-for-bluetooth-le-audio-products
66. https://www.sammobile.com/news/galaxy-s24-fe-launched-exynos-2400e-chip-worldwide/
67. https://www.dji.com/mic-mini/faq
68. https://www.sena.com/app
