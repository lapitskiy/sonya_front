/**
 * @file audio_cap.c
 * @brief I2S microphone capture
 *
 * TODO: Waveshare ESP32-S3 Touch AMOLED 2.06 uses ES7210 codec for dual mic.
 * ES7210 requires I2C init before I2S data is valid.
 * For minimal v0 we use raw I2S - if no sound, add ES7210 init via I2C.
 */

#include "audio_cap.h"
#include "esp_log.h"
#include "esp_check.h"
#include "driver/i2s_std.h"
#include "driver/i2c.h"
#include "es7210.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/ringbuf.h"
#include <string.h>
#include <inttypes.h>

static const char *TAG = "audio_cap";

#define I2C_MASTER_NUM I2C_NUM_0
#define ES7210_ADDR_DEFAULT 0x40
#define ES7210_ADDR_ALT 0x41

#ifndef CONFIG_I2C_SDA_GPIO
#define CONFIG_I2C_SDA_GPIO 15
#endif
#ifndef CONFIG_I2C_SCL_GPIO
#define CONFIG_I2C_SCL_GPIO 14
#endif

static i2s_chan_handle_t rx_handle = NULL;
static RingbufHandle_t ringbuf = NULL;
static bool capturing = false;
static TaskHandle_t capture_task = NULL;
static uint8_t s_es7210_addr = ES7210_ADDR_DEFAULT;
static es7210_dev_handle_t s_es7210 = NULL;

static esp_err_t i2c_probe_addr(uint8_t addr)
{
    i2c_cmd_handle_t cmd = i2c_cmd_link_create();
    i2c_master_start(cmd);
    i2c_master_write_byte(cmd, (addr << 1) | I2C_MASTER_WRITE, true);
    i2c_master_stop(cmd);
    esp_err_t err = i2c_master_cmd_begin(I2C_MASTER_NUM, cmd, pdMS_TO_TICKS(100));
    i2c_cmd_link_delete(cmd);
    return err;
}

static void i2c_scan_bus(void)
{
    ESP_LOGI(TAG, "I2C scan on SDA=%d SCL=%d ...", CONFIG_I2C_SDA_GPIO, CONFIG_I2C_SCL_GPIO);
    int found = 0;
    for (int addr = 0x03; addr <= 0x77; addr++) {
        if (i2c_probe_addr((uint8_t)addr) == ESP_OK) {
            ESP_LOGI(TAG, "I2C device found at 0x%02X", addr);
            found++;
        }
    }
    if (found == 0) {
        ESP_LOGW(TAG, "I2C scan: no devices found");
    }
}

static esp_err_t es7210_init_and_config(int sample_rate_hz)
{
    ESP_LOGI(TAG, "Init ES7210 using espressif/es7210 (addr=0x%02X)...", s_es7210_addr);

    if (s_es7210) {
        es7210_del_codec(s_es7210);
        s_es7210 = NULL;
    }

    es7210_i2c_config_t i2c_conf = {
        .i2c_port = I2C_MASTER_NUM,
        .i2c_addr = s_es7210_addr,
    };
    ESP_RETURN_ON_ERROR(es7210_new_codec(&i2c_conf, &s_es7210), TAG, "es7210_new_codec");

    es7210_codec_config_t cfg = {
        .sample_rate_hz = (uint32_t)sample_rate_hz,
        .mclk_ratio = 256,
        .i2s_format = ES7210_I2S_FMT_I2S,
        .bit_width = ES7210_I2S_BITS_16B,
        .mic_bias = ES7210_MIC_BIAS_2V78,
        .mic_gain = ES7210_MIC_GAIN_33DB,
        .flags = {
            .tdm_enable = 0,
        },
    };
    ESP_RETURN_ON_ERROR(es7210_config_codec(s_es7210, &cfg), TAG, "es7210_config_codec");
    ESP_LOGI(TAG, "ES7210 configured: sr=%d mclk_ratio=%u fmt=%u bits=%u tdm=%u",
             sample_rate_hz, (unsigned)cfg.mclk_ratio, (unsigned)cfg.i2s_format, (unsigned)cfg.bit_width,
             (unsigned)cfg.flags.tdm_enable);
    return ESP_OK;
}

#define RINGBUF_SIZE (16000 * 2 * 2)  /* ~2 sec at 16kHz 16bit mono */
#define DMA_BUF_COUNT 4
#define DMA_BUF_LEN 512

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
            if (dt_us >= 1000000) {
                // Effective sample rate based on received frames (stereo frame == 1 sample per channel per LRCK)
                uint32_t eff = (uint32_t)((uint64_t)frames_acc * 1000000ULL / dt_us);
                ESP_LOGI("audio_cap_diag", "eff_sr ~= %" PRIu32 " Hz (frames=%" PRIu32 " dt=%" PRIu64 "us)", eff, frames_acc, dt_us);
                t0_us = now_us;
                frames_acc = 0;
            }

            if (log_cnt++ % 20 == 0) { // Log every ~20 chunks
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
    int sda = CONFIG_I2C_SDA_GPIO;
    int scl = CONFIG_I2C_SCL_GPIO;

    // Initialize I2C
    i2c_config_t i2c_cfg = {
        .mode = I2C_MODE_MASTER,
        .sda_io_num = sda,
        .scl_io_num = scl,
        .sda_pullup_en = GPIO_PULLUP_ENABLE,
        .scl_pullup_en = GPIO_PULLUP_ENABLE,
        .master.clk_speed = 100000,
    };
    esp_err_t err = i2c_param_config(I2C_MASTER_NUM, &i2c_cfg);
    if (err != ESP_OK) return -1;
    err = i2c_driver_install(I2C_MASTER_NUM, i2c_cfg.mode, 0, 0, 0);
    if (err != ESP_OK) return -1;

    i2c_scan_bus();

    // Pick ES7210 address (some boards use 0x41)
    if (i2c_probe_addr(ES7210_ADDR_DEFAULT) == ESP_OK) {
        s_es7210_addr = ES7210_ADDR_DEFAULT;
    } else if (i2c_probe_addr(ES7210_ADDR_ALT) == ESP_OK) {
        s_es7210_addr = ES7210_ADDR_ALT;
    } else {
        ESP_LOGE(TAG, "ES7210 not found at 0x%02X/0x%02X (check I2C pins/address)", ES7210_ADDR_DEFAULT, ES7210_ADDR_ALT);
    }

    // Initialize ES7210
    if (es7210_init_and_config(sr) != ESP_OK) {
        ESP_LOGE(TAG, "ES7210 init/config failed");
    }

    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.auto_clear = true;
    err = i2s_new_channel(&chan_cfg, NULL, &rx_handle);
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
        i2s_del_channel(rx_handle);
        return -1;
    }

    ringbuf = xRingbufferCreate(RINGBUF_SIZE, RINGBUF_TYPE_BYTEBUF);
    if (!ringbuf) {
        ESP_LOGE(TAG, "ringbuf create fail");
        i2s_del_channel(rx_handle);
        return -1;
    }

    ESP_LOGI(TAG, "audio_cap init: %d Hz, bck=%d ws=%d din=%d mclk=%d i2c_sda=%d i2c_scl=%d es7210=0x%02X",
             sr, bck, ws, din, mclk, sda, scl, s_es7210_addr);
    return 0;
}

int audio_cap_start(void)
{
    if (!rx_handle) return -1;
    esp_err_t err = i2s_channel_enable(rx_handle);
    if (err) return -1;
    capturing = true;
    xTaskCreate(capture_task_fn, "audio_cap", 6144, NULL, 5, &capture_task);
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
