// MotoTalk H0 — radio test for the helmet unit (MotoTalk_Helmet_Unit.md, stage H0).
//
// The ESP32 is Bluetooth headphones (A2DP sink, music to the built-in DAC on GPIO25/26) and at
// the same time echoes every frame the app sends over Bluetooth serial (SPP). Once a second it
// sends its own counters as a STATE frame (JSON), so the app can tell whether the music stuttered
// while the voice-sized data stream was running.
//
// Helmet wiring without any parts (MotoTalk_Helmet_Unit.md, EJEAS pinout): left speaker (D-) to
// GPIO25, right speaker (D+) to GPIO26, the headset's GND left unconnected. The two speakers then
// sit in series between the DAC pins; the music goes out as mono in antiphase, so no DC flows
// through them and no capacitors are needed.
//
// Board: ESP32 Dev Module (classic ESP32 only: S3/C3/C6 have no Bluetooth Classic), core esp32
// 2.0.17, Partition Scheme "Huge APP (3MB No OTA)". Library: ESP32-A2DP 1.8.11 (pschatzmann).

#include "BluetoothSerial.h"
#include "BluetoothA2DPSinkQueued.h"
#include "esp_gap_bt_api.h"
#include "freertos/stream_buffer.h"

static const char *NAME = "MotoTalk-H0";

// MotoTalk wire format (POC requirements §5): 13-byte big-endian header
// version u8 · type u8 · seq u32 · timestampMs u32 · flags u8 · payloadLen u16, then the payload.
static const uint8_t VERSION = 1;
static const uint8_t T_AUDIO = 1;
static const uint8_t T_STATE = 5;
static const size_t HEADER = 13;
static const size_t MAX_PAYLOAD = 2048;
// Echoes older than this many frames are worthless for voice: drop instead of queueing.
static const size_t ECHO_BACKLOG_FRAMES = 6;

// --- A2DP sink that counts what the listener would hear -------------------------------------

class H0Sink : public BluetoothA2DPSinkQueued {
 public:
  volatile uint32_t underflows = 0;        // ring buffer ran dry while playing: audible stutter
  volatile uint32_t drops = 0;             // ring buffer full: a packet was thrown away
  volatile uint32_t minFill = UINT32_MAX;  // lowest ring buffer level this second, bytes
  volatile bool restart = true;            // playback (re)started: the next prefetch is not an underflow

  uint32_t takeMinFill() {
    uint32_t v = minFill;
    minFill = UINT32_MAX;
    return v;
  }

 protected:
  A2DPRingBufferMode lastMode = RINGBUFFER_MODE_PREFETCHING;

  size_t write_audio(const uint8_t *data, size_t size) override {
    A2DPRingBufferMode mode = ringbuffer_mode;
    if (restart) {
      lastMode = mode;
      restart = false;
    }
    if (lastMode == RINGBUFFER_MODE_PROCESSING && mode == RINGBUFFER_MODE_PREFETCHING) underflows++;
    if (mode == RINGBUFFER_MODE_DROPPING) drops++;
    if (mode == RINGBUFFER_MODE_PROCESSING && s_ringbuf_i2s) {
      UBaseType_t waiting = 0;
      vRingbufferGetInfo(s_ringbuf_i2s, nullptr, nullptr, nullptr, nullptr, &waiting);
      if (waiting < minFill) minFill = waiting;
    }
    size_t written = BluetoothA2DPSinkQueued::write_audio(data, size);
    lastMode = ringbuffer_mode;
    return written;
  }
};

BluetoothSerial SerialBT;
H0Sink sink;

static portMUX_TYPE statsMux = portMUX_INITIALIZER_UNLOCKED;
static uint32_t audioBytes = 0;    // decoded PCM received this second
static uint32_t maxGapUs = 0;      // longest pause between A2DP packets this second
static uint32_t lastPacketUs = 0;  // 0 = no packet since playback (re)started
static volatile esp_a2d_audio_state_t audioState = ESP_A2D_AUDIO_STATE_STOPPED;

// Stereo → mono in antiphase, in place: the DAC pins swing in opposite directions around the same
// midpoint, so the speakers in series between them see the music but never a DC voltage.
// One ear hears the sound inverted; for a test that is fine.
static void toBridge(uint8_t *data, uint32_t len) {
  int16_t *s = (int16_t *)data;
  for (uint32_t i = 0; i + 1 < len / 2; i += 2) {
    int32_t mono = ((int32_t)s[i] + s[i + 1]) / 2;
    s[i] = (int16_t)mono;
    s[i + 1] = (int16_t)(mono == -32768 ? 32767 : -mono);
  }
}

// Called on every decoded A2DP packet (after the phone's volume), before it goes to the ring buffer.
void onPcm(const uint8_t *data, uint32_t len) {
  uint32_t now = micros();
  portENTER_CRITICAL(&statsMux);
  if (lastPacketUs != 0 && now - lastPacketUs > maxGapUs) maxGapUs = now - lastPacketUs;
  lastPacketUs = now;
  audioBytes += len;
  portEXIT_CRITICAL(&statsMux);
  // The buffer is the library's own scratch copy (it rewrites it in place for the DAC too).
  toBridge((uint8_t *)data, len);
}

void onAudioState(esp_a2d_audio_state_t state, void *) {
  audioState = state;
  if (state == ESP_A2D_AUDIO_STATE_STARTED) {
    portENTER_CRITICAL(&statsMux);
    lastPacketUs = 0;  // the pause before Play is not a radio gap
    portEXIT_CRITICAL(&statsMux);
    sink.restart = true;
  }
}

// --- Data channel ------------------------------------------------------------------------------

static StreamBufferHandle_t rxBuf;  // BT task → loop
static StreamBufferHandle_t txBuf;  // loop → tx task
static volatile uint32_t rxOverflow = 0;
static volatile uint32_t txMaxMs = 0;  // longest SerialBT.write this second

static uint32_t rxFrames = 0, rxGaps = 0, rxBadBytes = 0;
static uint32_t txFrames = 0, txDropped = 0;
static int64_t lastSeq = -1;

void onSppData(const uint8_t *data, size_t len) {
  if (xStreamBufferSpacesAvailable(rxBuf) < len) {
    rxOverflow++;
    return;
  }
  xStreamBufferSend(rxBuf, data, len, 0);
}

// SerialBT.write can block for up to a second when the link is busy: never call it from loop().
void txTask(void *) {
  static uint8_t chunk[1024];
  for (;;) {
    size_t n = xStreamBufferReceive(txBuf, chunk, sizeof chunk, pdMS_TO_TICKS(100));
    if (n == 0 || !SerialBT.hasClient()) continue;
    uint32_t t0 = millis();
    SerialBT.write(chunk, n);
    uint32_t dt = millis() - t0;
    if (dt > txMaxMs) txMaxMs = dt;
  }
}

static uint32_t be32(const uint8_t *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }

// Queues a whole frame or nothing, so the stream to the phone stays parseable.
static bool queueFrame(const uint8_t *frame, size_t len) {
  if (xStreamBufferBytesAvailable(txBuf) + len > ECHO_BACKLOG_FRAMES * len ||
      xStreamBufferSpacesAvailable(txBuf) < len) {
    return false;
  }
  xStreamBufferSend(txBuf, frame, len, 0);
  return true;
}

static void handleFrame(const uint8_t *frame, size_t len) {
  if (frame[1] != T_AUDIO) return;
  rxFrames++;
  int64_t seq = be32(frame + 2);
  if (lastSeq >= 0 && seq > lastSeq + 1) rxGaps += seq - lastSeq - 1;
  lastSeq = seq;
  // Echo unchanged: the app gets its own seq and timestamp back and measures round trip and loss.
  if (queueFrame(frame, len)) txFrames++; else txDropped++;
}

static uint8_t acc[4096];
static size_t have = 0;

static void parse() {
  size_t pos = 0;
  while (have - pos >= HEADER) {
    const uint8_t *p = acc + pos;
    size_t len = (size_t)p[11] << 8 | p[12];
    if (p[0] != VERSION || p[1] == 0 || p[1] > 6 || len > MAX_PAYLOAD) {
      rxBadBytes++;  // out of step after an overflow: slide one byte and look again
      pos++;
      continue;
    }
    if (have - pos < HEADER + len) break;
    handleFrame(p, HEADER + len);
    pos += HEADER + len;
  }
  memmove(acc, acc + pos, have - pos);
  have -= pos;
}

static void sendStats() {
  portENTER_CRITICAL(&statsMux);
  uint32_t bytes = audioBytes, gapUs = maxGapUs;
  audioBytes = 0;
  maxGapUs = 0;
  portEXIT_CRITICAL(&statsMux);
  uint32_t bytesPerSec = (uint32_t)sink.sample_rate() * 4;  // 16-bit stereo
  uint32_t fill = sink.takeMinFill();
  uint32_t txMax = txMaxMs;
  txMaxMs = 0;

  char json[400];
  int n = snprintf(json, sizeof json,
      "{\"up\":%lu,\"a2dp\":%d,\"play\":%d,\"rate\":%u,\"aud_ms\":%lu,\"gap_ms\":%lu,\"fill_ms\":%ld,"
      "\"under\":%lu,\"drop\":%lu,\"spp\":%d,\"rx\":%lu,\"rx_gap\":%lu,\"rx_bad\":%lu,\"rx_ovf\":%lu,"
      "\"tx\":%lu,\"tx_drop\":%lu,\"tx_max_ms\":%lu,\"heap\":%lu,\"heap_min\":%lu}",
      (unsigned long)millis(), sink.is_connected() ? 1 : 0, audioState == ESP_A2D_AUDIO_STATE_STARTED ? 1 : 0,
      (unsigned)sink.sample_rate(), (unsigned long)((uint64_t)bytes * 1000 / bytesPerSec),
      (unsigned long)(gapUs / 1000), fill == UINT32_MAX ? -1L : (long)((uint64_t)fill * 1000 / bytesPerSec),
      (unsigned long)sink.underflows, (unsigned long)sink.drops, SerialBT.hasClient() ? 1 : 0,
      (unsigned long)rxFrames, (unsigned long)rxGaps, (unsigned long)rxBadBytes, (unsigned long)rxOverflow,
      (unsigned long)txFrames, (unsigned long)txDropped, (unsigned long)txMax,
      (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMinFreeHeap());
  if (n >= (int)sizeof json) n = sizeof json - 1;
  Serial.println(json);
  if (!SerialBT.hasClient() || n <= 0) return;

  static uint8_t frame[HEADER + sizeof json];
  static uint32_t stateSeq = 0;
  uint32_t now = millis();
  uint8_t h[HEADER] = {VERSION, T_STATE,
                       (uint8_t)(stateSeq >> 24), (uint8_t)(stateSeq >> 16), (uint8_t)(stateSeq >> 8), (uint8_t)stateSeq,
                       (uint8_t)(now >> 24), (uint8_t)(now >> 16), (uint8_t)(now >> 8), (uint8_t)now,
                       0, (uint8_t)(n >> 8), (uint8_t)n};
  stateSeq++;
  memcpy(frame, h, HEADER);
  memcpy(frame + HEADER, json, n);
  // A busy link may refuse it: the next second's counters are cumulative, nothing is lost.
  if (xStreamBufferSpacesAvailable(txBuf) >= HEADER + n) xStreamBufferSend(txBuf, frame, HEADER + n, 0);
}

void setup() {
  Serial.begin(115200);
  rxBuf = xStreamBufferCreate(8192, 1);
  txBuf = xStreamBufferCreate(8192, 1);

  // Order matters: both libraries install the same GAP callback and the last one wins; A2DP needs it.
  SerialBT.onData(onSppData);
  SerialBT.begin(NAME);

  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN);
  cfg.sample_rate = 44100;  // corrected by the phone
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;  // the DAC takes the top 8 bits
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
  cfg.communication_format = (i2s_comm_format_t)I2S_COMM_FORMAT_STAND_MSB;
  cfg.intr_alloc_flags = 0;
  cfg.dma_buf_count = 8;
  cfg.dma_buf_len = 64;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = true;  // silence instead of a buzz when the buffer runs dry
  sink.set_i2s_config(cfg);
  sink.set_stream_reader(onPcm, true);
  sink.set_on_audio_state_changed(onAudioState);
  sink.start(NAME);

  // BluetoothSerial announces a computer; show the phone a headphones icon instead.
  esp_bt_cod_t cod = {};
  cod.major = ESP_BT_COD_MAJOR_DEV_AV;
  cod.minor = 6;  // headphones
  cod.service = ESP_BT_COD_SRVC_RENDERING | ESP_BT_COD_SRVC_AUDIO;  // bits of the 11-bit service field
  esp_bt_gap_set_cod(cod, ESP_BT_SET_COD_ALL);

  xTaskCreatePinnedToCore(txTask, "h0tx", 4096, nullptr, 5, nullptr, 1);
  Serial.printf("MotoTalk H0 ready, free heap %u\n", ESP.getFreeHeap());
}

void loop() {
  static bool client = false;
  if (SerialBT.hasClient() != client) {
    client = !client;
    lastSeq = -1;  // a new run starts its own numbering
    have = 0;
    Serial.println(client ? "app connected" : "app disconnected");
  }

  size_t n = xStreamBufferReceive(rxBuf, acc + have, sizeof acc - have, pdMS_TO_TICKS(10));
  if (n > 0) {
    have += n;
    parse();
  }

  static uint32_t nextStats = 0;
  if ((int32_t)(millis() - nextStats) >= 0) {
    nextStats = millis() + 1000;
    sendStats();
  }
}
