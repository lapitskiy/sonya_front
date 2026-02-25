/**
 * @file app_main.c
 * @brief Sonya Watch firmware v0
 *
 * BUTTON mode: hold button = record, release = REC_END.
 * CMD mode: REC command -> record fixed duration.
 */

#include <stdio.h>
#include <stdint.h>
#include "esp_log.h"
#include "nvs_flash.h"
#include "sonya_ble.h"
#include "audio_cap.h"
#include "wake_engine.h"
#include "protocol.h"
#include "rec_store.h"
#include "pull_stream.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "status_ui.h"

static const char *TAG = "main";

#define REC_MAX_SEC 30

static int s_rec_seconds = CONFIG_REC_SECONDS;
static volatile bool s_is_recording = false;

/* ---- button helpers ---- */

static inline bool btn_is_down(void)
{
#if defined(CONFIG_WAKE_MODE_BUTTON)
    int level = gpio_get_level(CONFIG_WAKE_BUTTON_GPIO);
#if defined(CONFIG_WAKE_BUTTON_ACTIVE_HIGH)
    return level != 0;
#else
    return level == 0;
#endif
#else
    return false;
#endif
}

static bool btn_released_stable_ms(int stable_ms)
{
#if defined(CONFIG_WAKE_MODE_BUTTON)
    const int max_wait_ms = 1500;
    int stable = 0;
    int total = 0;
    while (stable < stable_ms && total < max_wait_ms) {
        if (btn_is_down()) {
            stable = 0;
        } else {
            stable += 10;
        }
        vTaskDelay(pdMS_TO_TICKS(10));
        total += 10;
    }
    return stable >= stable_ms;
#else
    (void)stable_ms;
    return true;
#endif
}

/* ---- BLE RX handler ---- */

static void on_ble_rx(const uint8_t *data, uint16_t len, void *arg)
{
    int setrec_val = 0;
    uint16_t rec_id = 0;
    uint32_t off = 0;
    uint16_t want_len = 0;
    proto_cmd_t cmd = proto_parse_rx_cmd(data, len, &setrec_val, &rec_id, &off, &want_len);

    switch (cmd) {
    case PROTO_CMD_PING:
        ESP_LOGI(TAG, "RX: PING");
        if (sonya_ble_is_connected())
            sonya_ble_send_evt_error("PONG");
        break;
    case PROTO_CMD_REC:
        ESP_LOGI(TAG, "RX: REC (rec_seconds=%d)", s_rec_seconds);
        wake_on_rx_cmd("REC");
        break;
    case PROTO_CMD_SETREC:
        s_rec_seconds = setrec_val;
        ESP_LOGI(TAG, "RX: SETREC -> %d sec", s_rec_seconds);
        if (sonya_ble_is_connected()) {
            char msg[32];
            snprintf(msg, sizeof(msg), "REC_SEC=%d", s_rec_seconds);
            sonya_ble_send_evt_error(msg);
        }
        break;
    case PROTO_CMD_GET:
        pull_stream_handle_get(rec_id, off, want_len);
        break;
    case PROTO_CMD_DONE:
        pull_stream_handle_done(rec_id);
        break;
    default:
        ESP_LOGW(TAG, "RX: unknown cmd (%d bytes)", len);
        break;
    }
}

/* ---- send REC_END meta ---- */

static void send_rec_end_meta(void)
{
    uint16_t rid   = rec_store_cur_id();
    uint32_t total = (uint32_t)rec_store_total_bytes();
    uint32_t crc   = rec_store_crc32();
    uint16_t sr16  = (uint16_t)CONFIG_AUDIO_SR;

    uint8_t meta[2 + 4 + 4 + 2];
    meta[0]  = (uint8_t)(rid   & 0xFF);
    meta[1]  = (uint8_t)(rid   >> 8);
    meta[2]  = (uint8_t)(total & 0xFF);
    meta[3]  = (uint8_t)((total >>  8) & 0xFF);
    meta[4]  = (uint8_t)((total >> 16) & 0xFF);
    meta[5]  = (uint8_t)((total >> 24) & 0xFF);
    meta[6]  = (uint8_t)(crc   & 0xFF);
    meta[7]  = (uint8_t)((crc  >>  8) & 0xFF);
    meta[8]  = (uint8_t)((crc  >> 16) & 0xFF);
    meta[9]  = (uint8_t)((crc  >> 24) & 0xFF);
    meta[10] = (uint8_t)(sr16  & 0xFF);
    meta[11] = (uint8_t)(sr16  >> 8);
    sonya_ble_send_frame(PROTO_EVT_REC_END, meta, (uint16_t)sizeof(meta));
}

/* ---- recording (BUTTON mode) ---- */

#if defined(CONFIG_WAKE_MODE_BUTTON)
static void record_button(int want)
{
    TickType_t rec_start_tick = xTaskGetTickCount();
#define HOLD_MIN_MS 400
#define RELEASE_DEBOUNCE_MS 300
    int gpio_num = CONFIG_WAKE_BUTTON_GPIO;
    ESP_LOGI(TAG, "rec start: gpio%d=%d btn_down=%d",
             gpio_num, gpio_get_level(gpio_num), btn_is_down() ? 1 : 0);
    int loop_count = 0;
    int prev_down = btn_is_down() ? 1 : 0;
    TickType_t next_hb = rec_start_tick + pdMS_TO_TICKS(500);
    uint32_t pcm_win_samples = 0;
    uint64_t pcm_win_sum_abs = 0;
    uint16_t pcm_win_max_abs = 0;

    int got = 0;
    bool alloc_failed = false;

    while (got < want) {
        loop_count++;
        TickType_t now = xTaskGetTickCount();
        TickType_t elapsed = now - rec_start_tick;
        int down = btn_is_down() ? 1 : 0;
        int gpio_lvl = gpio_get_level(CONFIG_WAKE_BUTTON_GPIO);

        if (down != prev_down) {
            ESP_LOGI(TAG, "btn edge: elapsed_ticks=%lu gpio=%d btn_down=%d",
                     (unsigned long)elapsed, gpio_lvl, down);
            prev_down = down;
        }
        if (now >= next_hb) {
            if (pcm_win_samples > 0) {
                uint32_t avg_abs = (uint32_t)(pcm_win_sum_abs / pcm_win_samples);
                ESP_LOGI(TAG, "mic: win500ms samples=%u maxAbs=%u avgAbs=%u",
                         (unsigned)pcm_win_samples, (unsigned)pcm_win_max_abs, (unsigned)avg_abs);
                if (pcm_win_max_abs < 80)
                    ESP_LOGW(TAG, "mic looks like silence (maxAbs<80)");
            } else {
                ESP_LOGW(TAG, "mic: no samples in window");
            }
            ESP_LOGI(TAG, "rec hb: elapsed_ticks=%lu gpio=%d btn_down=%d got=%d",
                     (unsigned long)elapsed, gpio_lvl, down, got);
            next_hb = now + pdMS_TO_TICKS(500);
            pcm_win_samples = 0;
            pcm_win_sum_abs = 0;
            pcm_win_max_abs = 0;
        }
        if (loop_count <= 5) {
            ESP_LOGI(TAG, "loop iter=%d elapsed_ticks=%lu gpio=%d btn_down=%d got=%d",
                     loop_count, (unsigned long)elapsed, gpio_lvl, down, got);
        }
        if (elapsed >= pdMS_TO_TICKS(HOLD_MIN_MS) && !btn_is_down()) {
            ESP_LOGI(TAG, "release candidate -> debounce %d ms", RELEASE_DEBOUNCE_MS);
            bool stable = btn_released_stable_ms(RELEASE_DEBOUNCE_MS);
            int still_down = btn_is_down() ? 1 : 0;
            ESP_LOGI(TAG, "release debounce done stable=%d gpio=%d btn_down=%d",
                     stable ? 1 : 0, gpio_get_level(CONFIG_WAKE_BUTTON_GPIO), still_down);
            if (!still_down) {
                ESP_LOGI(TAG, "button released -> end");
                break;
            }
        }

        /* ensure we have a block with room */
        size_t room = 0;
        uint8_t *ptr = rec_store_tail_ptr(&room);
        if (!ptr || room == 0) {
            if (!rec_store_alloc_block()) {
                alloc_failed = true;
                ESP_LOGE(TAG, "REC_END reason: no mem got=%d", got);
                status_ui_set_error(true);
                if (sonya_ble_is_connected())
                    sonya_ble_send_evt_error("no mem");
                break;
            }
            ptr = rec_store_tail_ptr(&room);
        }

        int to_read = want - got;
        if ((size_t)to_read > room) to_read = (int)room;
        if (to_read > 1024) to_read = 1024;
        int r = audio_cap_read(ptr, (size_t)to_read, 50);
        if (r < 0) {
            ESP_LOGE(TAG, "REC_END reason: audio_cap_read fail %d (got=%d)", r, got);
            status_ui_set_error(true);
            if (sonya_ble_is_connected())
                sonya_ble_send_evt_error("audio read fail");
            break;
        }
        if (r == 0) continue;
        if (r & 1) r--;
        if (r <= 0) continue;

        {
            size_t n = (size_t)r / 2;
            const int16_t *s = (const int16_t *)ptr;
            for (size_t i = 0; i < n; i++) {
                int16_t v = s[i];
                uint16_t a = (uint16_t)(v < 0 ? (uint16_t)(-v) : (uint16_t)v);
                pcm_win_sum_abs += a;
                if (a > pcm_win_max_abs) pcm_win_max_abs = a;
            }
            pcm_win_samples += (uint32_t)n;
        }

        rec_store_tail_advance((size_t)r);
        got += r;
    }

    if (alloc_failed)
        ESP_LOGW(TAG, "recording truncated due to alloc failure: bytes=%d", got);

    ESP_LOGI(TAG, "REC_END bytes=%d", got);
}
#endif /* CONFIG_WAKE_MODE_BUTTON */

/* ---- recording (CMD mode) ---- */

#if !defined(CONFIG_WAKE_MODE_BUTTON)
static void record_cmd(int cap_sec, int want)
{
    size_t buf_size = (size_t)want;
    uint8_t *buf = (uint8_t *)heap_caps_malloc(buf_size, MALLOC_CAP_8BIT);
    if (!buf) buf = (uint8_t *)heap_caps_malloc(buf_size, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!buf) {
        ESP_LOGE(TAG, "REC_END reason: no mem for buffer (%u bytes)", (unsigned)buf_size);
        status_ui_set_error(true);
        if (sonya_ble_is_connected())
            sonya_ble_send_evt_error("no mem");
        return;
    }

    int rec_bytes = audio_cap_record_segment(buf, buf_size, cap_sec);
    if (rec_bytes < 0) {
        ESP_LOGE(TAG, "REC_END reason: record_segment fail %d", rec_bytes);
        status_ui_set_error(true);
        if (sonya_ble_is_connected())
            sonya_ble_send_evt_error("audio record fail");
        heap_caps_free(buf);
        return;
    }

    rec_store_append(buf, (size_t)rec_bytes);
    heap_caps_free(buf);

    ESP_LOGI(TAG, "REC_END bytes=%d", rec_bytes);
}
#endif /* !CONFIG_WAKE_MODE_BUTTON */

/* ---- app_main ---- */

void app_main(void)
{
    ESP_LOGI(TAG, "boot");

    status_ui_init();

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    ESP_LOGI(TAG, "NVS ok");

    err = sonya_ble_init(CONFIG_DEVICE_NAME, on_ble_rx, NULL);
    if (err) {
        ESP_LOGE(TAG, "ble_init fail %d", err);
        return;
    }
    ESP_LOGI(TAG, "BLE up");
    esp_log_level_set("NimBLE", ESP_LOG_WARN);

    err = audio_cap_init();
    if (err) {
        ESP_LOGE(TAG, "audio_cap_init fail %d", err);
        sonya_ble_send_evt_error("audio init fail");
        return;
    }
    err = audio_cap_start();
    if (err) {
        ESP_LOGE(TAG, "audio_cap_start fail %d", err);
        return;
    }
    ESP_LOGI(TAG, "audio capture running");

    err = wake_init(WAKE_MODE_CMD);
    if (err) {
        ESP_LOGE(TAG, "wake_init fail %d", err);
        return;
    }
    ESP_LOGI(TAG, "wake init ok");

    err = pull_stream_init();
    if (err) {
        ESP_LOGE(TAG, "pull_stream_init fail %d", err);
        return;
    }

    status_ui_set_recording(false);

    TickType_t boot_ready = xTaskGetTickCount() + pdMS_TO_TICKS(2000);

    for (;;) {
        bool trig = wake_poll_or_wait(100);
        if (!trig) continue;
        TickType_t now = xTaskGetTickCount();
#if defined(CONFIG_WAKE_MODE_BUTTON)
        ESP_LOGI(TAG, "wake trig: ticks=%lu ble=%d gpio=%d",
                 (unsigned long)now, sonya_ble_is_connected() ? 1 : 0,
                 gpio_get_level(CONFIG_WAKE_BUTTON_GPIO));
#else
        ESP_LOGI(TAG, "wake trig: ticks=%lu ble=%d", (unsigned long)now, sonya_ble_is_connected() ? 1 : 0);
#endif
        if (now < boot_ready) {
            ESP_LOGI(TAG, "wake ignored: boot warmup");
            continue;
        }
        if (s_is_recording) {
            ESP_LOGW(TAG, "wake ignored: already recording");
            continue;
        }
        s_is_recording = true;

        ESP_LOGI(TAG, "wake detected, confidence=%u", wake_get_confidence());
#if defined(CONFIG_WAKE_MODE_BUTTON)
        if (!sonya_ble_is_connected()) {
            ESP_LOGW(TAG, "button trigger but BLE not connected -> ignore");
            status_ui_set_error(true);
            status_ui_set_recording(false);
            s_is_recording = false;
            continue;
        }
#endif
        if (sonya_ble_is_connected())
            sonya_ble_send_evt_wake();

        int cap_sec =
#if defined(CONFIG_WAKE_MODE_BUTTON)
            REC_MAX_SEC;
#else
            s_rec_seconds;
#endif
        if (cap_sec < 1) cap_sec = 1;
        if (cap_sec > REC_MAX_SEC) cap_sec = REC_MAX_SEC;

        // Prevent wake re-trigger while we're recording (WWE can keep detecting in background).
        wake_suspend_ms((uint32_t)cap_sec * 1000U + 1500U);

        int sr = CONFIG_AUDIO_SR;
        int want = cap_sec * sr * 2;

        ESP_LOGI(TAG, "REC_START cap=%d sec sr=%d want=%d", cap_sec, sr, want);
        status_ui_set_recording(true);
        status_ui_set_error(false);
        audio_cap_flush();

        uint16_t rid = rec_store_begin();

        if (sonya_ble_is_connected()) {
            sonya_ble_send_evt_rec_start();
            pull_stream_start_live(rid);
        }

#if defined(CONFIG_WAKE_MODE_BUTTON)
        record_button(want);
#else
        record_cmd(cap_sec, want);
#endif

        pull_stream_stop_live();
        rec_store_commit();

        status_ui_set_recording(false);

        if (sonya_ble_is_connected()) {
            send_rec_end_meta();
            ESP_LOGI(TAG, "REC_END meta sent: id=%u bytes=%d",
                     (unsigned)rec_store_cur_id(), rec_store_total_bytes());
        } else {
            ESP_LOGI(TAG, "recorded %d bytes (no BLE, dropped)", rec_store_total_bytes());
        }

        s_is_recording = false;
    }
}
