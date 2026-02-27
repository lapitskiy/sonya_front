/**
 * @file audio_cap.c
 * @brief I2S microphone capture
 *
 * Waveshare ESP32-S3 Touch AMOLED 2.06 uses ES7210 codec (mic) and ES8311 (speaker).
 * Use esp_codec_dev to configure codecs via shared I2C.
 */

#include "audio_cap.h"
#include "esp_log.h"
#include "esp_check.h"
#include "driver/i2s_std.h"
#include "esp_codec_dev_defaults.h"
#include "esp_codec_dev.h"
#include "audio_codec_ctrl_if.h"
#include "audio_codec_data_if.h"
#include "audio_codec_gpio_if.h"
#include "es7210_adc.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/ringbuf.h"
#include "sonya_board.h"
#include <string.h>
#include <inttypes.h>

static const char *TAG = "audio_cap";

static i2s_chan_handle_t rx_handle = NULL;
static RingbufHandle_t ringbuf = NULL;
static bool capturing = false;
static TaskHandle_t capture_task = NULL;
static const audio_codec_data_if_t *s_i2s_data_if = NULL;
static esp_codec_dev_handle_t s_mic = NULL;
static i2s_chan_handle_t s_tx_handle = NULL; // unused, but kept for esp_codec_dev compatibility

#define RINGBUF_SIZE (16000 * 2 * 2)  /* ~2 sec at 16kHz 16bit mono */
#define DMA_BUF_COUNT 4
#define DMA_BUF_LEN 512

static int init_mic_codec(int sr)
{
    i2c_master_bus_handle_t bus = sonya_board_i2c_bus();
    if (!bus) return -1;

    /* Bind esp_codec_dev to I2S handles (it uses these for clocking & data path) */
    audio_codec_i2s_cfg_t i2s_cfg = {
        .port = I2S_NUM_0,
        .rx_handle = rx_handle,
        .tx_handle = s_tx_handle,
    };
    s_i2s_data_if = audio_codec_new_i2s_data(&i2s_cfg);
    if (!s_i2s_data_if) {
        ESP_LOGE(TAG, "audio_codec_new_i2s_data failed");
        return -1;
    }

    audio_codec_i2c_cfg_t i2c_cfg = {
        .port = I2C_NUM_0,
        .addr = ES7210_CODEC_DEFAULT_ADDR,
        .bus_handle = bus,
    };
    const audio_codec_ctrl_if_t *i2c_ctrl_if = audio_codec_new_i2c_ctrl(&i2c_cfg);
    if (!i2c_ctrl_if) {
        ESP_LOGE(TAG, "audio_codec_new_i2c_ctrl failed");
        return -1;
    }

    es7210_codec_cfg_t es7210_cfg = {
        .ctrl_if = i2c_ctrl_if,
    };
    const audio_codec_if_t *es7210_dev = es7210_codec_new(&es7210_cfg);
    if (!es7210_dev) {
        ESP_LOGE(TAG, "es7210_codec_new failed");
        return -1;
    }

    esp_codec_dev_cfg_t dev_cfg = {
        .dev_type = ESP_CODEC_DEV_TYPE_IN,
        .codec_if = es7210_dev,
        .data_if = s_i2s_data_if,
    };
    s_mic = esp_codec_dev_new(&dev_cfg);
    if (!s_mic) {
        ESP_LOGE(TAG, "esp_codec_dev_new(mic) failed");
        return -1;
    }

    esp_codec_dev_sample_info_t fs = {
        .sample_rate = (uint32_t)sr,
        .channel = I2S_SLOT_MODE_STEREO,
        .bits_per_sample = I2S_DATA_BIT_WIDTH_16BIT,
    };
    esp_err_t err = esp_codec_dev_open(s_mic, &fs);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_codec_dev_open(mic) failed: %d", err);
        return -1;
    }
    (void)esp_codec_dev_set_in_gain(s_mic, (float)CONFIG_AUDIO_IN_GAIN_DB);
    ESP_LOGI(TAG, "mic gain: %d dB", (int)CONFIG_AUDIO_IN_GAIN_DB);
    return 0;
}

static void capture_task_fn(void *arg)
{
    // Stereo 16-bit => 4 bytes per frame
    uint8_t tmp[DMA_BUF_LEN * 4];
    size_t r;
    int log_cnt = 0;
    uint64_t t0_us = esp_timer_get_time();
    uint32_t frames_acc = 0;

    while (capturing && rx_handle) {
        esp_err_t err = i2s_channel_read(rx_handle, tmp, sizeof(tmp), &r, portMAX_DELAY);
        if (err == ESP_OK && r > 0) {
            frames_acc += (uint32_t)(r / 4);
            uint64_t now_us = esp_timer_get_time();
            uint64_t dt_us = now_us - t0_us;
            // Reduce log spam: effective SR is useful, but not every second.
            if (dt_us >= 10ULL * 1000000ULL) {
                // Effective sample rate based on received frames (stereo frame == 1 sample per channel per LRCK)
                uint32_t eff = (uint32_t)((uint64_t)frames_acc * 1000000ULL / dt_us);
                ESP_LOGI("audio_cap_diag", "eff_sr ~= %" PRIu32 " Hz (frames=%" PRIu32 " dt=%" PRIu64 "us)", eff, frames_acc, dt_us);
                t0_us = now_us;
                frames_acc = 0;
            }

            // Log mic stats at ~1Hz to correlate with spoken wake words.
            if (log_cnt++ % 30 == 0) { // ~1 line per second at 16kHz/512 frames
                int frames = (int)(r / 4);
                const int16_t *lr = (const int16_t *)tmp;
                int32_t maxL = 0, maxR = 0;
                int64_t sumL = 0, sumR = 0;
                for (int i = 0; i < frames; i++) {
                    int32_t l = lr[i * 2 + 0];
                    int32_t rr = lr[i * 2 + 1];
                    int32_t al = l < 0 ? -l : l;
                    int32_t ar = rr < 0 ? -rr : rr;
                    if (al > maxL) maxL = al;
                    if (ar > maxR) maxR = ar;
                    sumL += al;
                    sumR += ar;
                }
                int avgL = frames > 0 ? (int)(sumL / frames) : 0;
                int avgR = frames > 0 ? (int)(sumR / frames) : 0;
                ESP_LOGI("audio_cap_diag", "mic16_stereo: bytes=%u frames=%d L(max=%" PRId32 " avg=%d) R(max=%" PRId32 " avg=%d)",
                         (unsigned)r, frames, maxL, avgL, maxR, avgR);
            }

            // Downmix to mono, but first auto-pick the channel with stronger signal.
            // Some boards route the mic to only one ES7210 slot; the other slot can look like noise.
            int frames = (int)(r / 4);
            if (frames <= 0) continue;
            const int16_t *lr = (const int16_t *)tmp;

            int32_t maxL = 0, maxR = 0;
            for (int i = 0; i < frames; i++) {
                int32_t l = lr[i * 2 + 0];
                int32_t rr = lr[i * 2 + 1];
                int32_t al = l < 0 ? -l : l;
                int32_t ar = rr < 0 ? -rr : rr;
                if (al > maxL) maxL = al;
                if (ar > maxR) maxR = ar;
            }
            bool use_left = (maxL >= maxR);

            int16_t out[DMA_BUF_LEN];
            int out_samples = frames;
            if (out_samples > (int)(sizeof(out) / sizeof(out[0]))) out_samples = (int)(sizeof(out) / sizeof(out[0]));
            for (int i = 0; i < out_samples; i++) {
                out[i] = lr[i * 2 + (use_left ? 0 : 1)];
            }

            if (ringbuf) {
                xRingbufferSend(ringbuf, (uint8_t *)out, (size_t)(out_samples * 2), 0);
            }
        }
    }
    capture_task = NULL;
    vTaskDelete(NULL);
}

int audio_cap_init(void)
{
    int sr = CONFIG_AUDIO_SR;
    int bck = CONFIG_I2S_BCK_GPIO;
    int ws = CONFIG_I2S_WS_GPIO;
    int din = CONFIG_I2S_DIN_GPIO;
    int mclk = CONFIG_I2S_MCLK_GPIO;

    // Shared I2C bus (touch + codecs)
    if (sonya_board_i2c_init() != ESP_OK) return -1;

    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.auto_clear = true;
    esp_err_t err = i2s_new_channel(&chan_cfg, &s_tx_handle, &rx_handle);
    if (err) {
        ESP_LOGE(TAG, "i2s_new_channel %d", err);
        return -1;
    }

    i2s_std_config_t std_cfg = {
        .clk_cfg = {
            .sample_rate_hz = sr,
            // APLL gives much more accurate audio sample rates; default clock can drift and cause speed/pitch issues.
#ifdef I2S_CLK_SRC_APLL
            .clk_src = I2S_CLK_SRC_APLL,
#else
            .clk_src = I2S_CLK_SRC_DEFAULT,
#endif
            .mclk_multiple = I2S_MCLK_MULTIPLE_256,
        },
        .slot_cfg = I2S_STD_PHILIP_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = mclk >= 0 ? mclk : I2S_GPIO_UNUSED,
            .bclk = bck,
            .ws = ws,
            .dout = I2S_GPIO_UNUSED,
            .din = din,
            .invert_flags = {
                .mclk_inv = false,
                .bclk_inv = false,
                .ws_inv = false,
            },
        },
    };

    err = i2s_channel_init_std_mode(rx_handle, &std_cfg);
    if (err) {
        ESP_LOGE(TAG, "i2s_channel_init %d", err);
        i2s_del_channel(s_tx_handle);
        i2s_del_channel(rx_handle);
        return -1;
    }

    err = i2s_channel_init_std_mode(s_tx_handle, &std_cfg);
    if (err) {
        ESP_LOGE(TAG, "i2s_channel_init (tx) %d", err);
        i2s_del_channel(s_tx_handle);
        i2s_del_channel(rx_handle);
        return -1;
    }

    if (init_mic_codec(sr) != 0) {
        ESP_LOGE(TAG, "init_mic_codec failed");
        i2s_del_channel(s_tx_handle);
        i2s_del_channel(rx_handle);
        return -1;
    }

    ringbuf = xRingbufferCreate(RINGBUF_SIZE, RINGBUF_TYPE_BYTEBUF);
    if (!ringbuf) {
        ESP_LOGE(TAG, "ringbuf create fail");
        (void)esp_codec_dev_close(s_mic);
        i2s_del_channel(rx_handle);
        i2s_del_channel(s_tx_handle);
        return -1;
    }

    ESP_LOGI(TAG, "audio_cap init: %d Hz, bck=%d ws=%d din=%d mclk=%d (codec via esp_codec_dev)",
             sr, bck, ws, din, mclk);
    return 0;
}

int audio_cap_start(void)
{
    if (!rx_handle) return -1;
    // esp_codec_dev (via audio_codec_new_i2s_data + esp_codec_dev_open) may already
    // enable the I2S channel. Make start idempotent by forcing a clean disable->enable.
    esp_err_t err = i2s_channel_disable(rx_handle);
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "i2s_channel_disable(rx) failed: %s (%d)", esp_err_to_name(err), (int)err);
        return -1;
    }

    err = i2s_channel_enable(rx_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "i2s_channel_enable(rx) failed: %s (%d)", esp_err_to_name(err), (int)err);
        return -1;
    }
    capturing = true;
    if (xTaskCreate(capture_task_fn, "audio_cap", 6144, NULL, 5, &capture_task) != pdPASS) {
        ESP_LOGE(TAG, "xTaskCreate(audio_cap) failed");
        capturing = false;
        (void)i2s_channel_disable(rx_handle);
        return -1;
    }
    ESP_LOGI(TAG, "audio capture started");
    return 0;
}

void audio_cap_stop(void)
{
    capturing = false;
    while (capture_task) {
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    if (rx_handle) {
        i2s_channel_disable(rx_handle);
    }
    if (s_tx_handle) {
        i2s_channel_disable(s_tx_handle);
    }
    ESP_LOGI(TAG, "audio capture stopped");
}

void audio_cap_flush(void)
{
    if (!ringbuf) return;
    size_t item_size = 0;
    for (;;) {
        uint8_t *item = (uint8_t *)xRingbufferReceiveUpTo(ringbuf, &item_size, 0, 4096);
        if (!item) break;
        vRingbufferReturnItem(ringbuf, item);
    }
}

int audio_cap_read(uint8_t *buf, size_t max_len, uint32_t timeout_ms)
{
    if (!buf || !ringbuf) return -1;
    if (max_len == 0) return 0;

    size_t item_size = 0;
    uint8_t *item = (uint8_t *)xRingbufferReceiveUpTo(
        ringbuf, &item_size, pdMS_TO_TICKS(timeout_ms), max_len
    );
    if (!item) return 0;

    memcpy(buf, item, item_size);
    vRingbufferReturnItem(ringbuf, item);
    return (int)item_size;
}

int audio_cap_record_segment(uint8_t *buf, size_t buf_size, int rec_seconds)
{
    if (!buf || !ringbuf) return -1;
    int rec_sec = rec_seconds > 0 ? rec_seconds : CONFIG_REC_SECONDS;
    int sr = CONFIG_AUDIO_SR;
    size_t want = (size_t)(rec_sec * sr * 2);  /* 16-bit = 2 bytes/sample */
    if (want > buf_size) want = buf_size;

    size_t got = 0;
    while (got < want) {
        size_t item_size;
        uint8_t *item = (uint8_t *)xRingbufferReceiveUpTo(ringbuf, &item_size,
                                                          pdMS_TO_TICKS(100), want - got);
        if (!item) continue;
        size_t copy = item_size;
        if (got + copy > want) copy = want - got;
        memcpy(buf + got, item, copy);
        vRingbufferReturnItem(ringbuf, item);
        got += copy;
    }
    ESP_LOGI(TAG, "recorded %u bytes (%d sec)", (unsigned)got, rec_sec);
    return (int)got;
}
