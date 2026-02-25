/**
 * @file wake.c
 * @brief Wake engine: CMD/BUTTON + WakeNet (esp-sr)
 */

#include "wake_engine.h"
#include "audio_cap.h"
#include "esp_log.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_err.h"
#include "esp_afe_sr_models.h"
#include "model_path.h"
#include <string.h>
#include <stdlib.h>

static const char *TAG = "wake";

static wake_mode_t s_mode = WAKE_MODE_CMD;
static bool s_wake_pending = false;
static uint8_t s_confidence = 100;
static QueueHandle_t s_cmd_queue = NULL;
static TickType_t s_last_wake_tick = 0;
static bool s_button_armed = true; // one trigger per press (wait for release)
static TickType_t s_release_since_tick = 0; // require stable release before re-arming
static TickType_t s_suspend_until_tick = 0;

#define CMD_QUEUE_LEN 4
#define CMD_MAX_LEN 32
#define BUTTON_REARM_RELEASE_MS 300

typedef enum {
    WAKE_SRC_NONE = 0,
    WAKE_SRC_BUTTON,
    WAKE_SRC_WWE,
    WAKE_SRC_CMD,
} wake_src_t;

static volatile wake_src_t s_last_src = WAKE_SRC_NONE;

/* ---- WakeNet (esp-sr) ---- */

static const esp_afe_sr_iface_t *s_afe = NULL;
static esp_afe_sr_data_t *s_afe_data = NULL;
static afe_config_t *s_afe_cfg = NULL;
static srmodel_list_t *s_models = NULL;
static TaskHandle_t s_feed_task = NULL;
static TaskHandle_t s_fetch_task = NULL;
static volatile bool s_wwe_running = false;

static inline bool is_suspended_now(void)
{
    if (s_suspend_until_tick == 0) return false;
    TickType_t now = xTaskGetTickCount();
    return now < s_suspend_until_tick;
}

void wake_suspend_ms(uint32_t ms)
{
    TickType_t now = xTaskGetTickCount();
    s_suspend_until_tick = now + pdMS_TO_TICKS(ms);
    s_wake_pending = false;
}

static void wwe_feed_task(void *arg)
{
    (void)arg;
    if (!s_afe || !s_afe_data) {
        vTaskDelete(NULL);
        return;
    }

    int feed_chunksize = s_afe->get_feed_chunksize(s_afe_data);
    int feed_nch = s_afe->get_feed_channel_num(s_afe_data);
    if (feed_chunksize <= 0 || feed_nch <= 0) {
        ESP_LOGE(TAG, "WWE feed config invalid: chunk=%d nch=%d", feed_chunksize, feed_nch);
        vTaskDelete(NULL);
        return;
    }

    if (feed_nch != 1 && feed_nch != 2) {
        ESP_LOGE(TAG, "WWE unsupported feed_nch=%d (expected 1 or 2)", feed_nch);
        vTaskDelete(NULL);
        return;
    }

    size_t mic_bytes = (size_t)feed_chunksize * sizeof(int16_t);
    size_t feed_samples = (size_t)feed_chunksize * (size_t)feed_nch;
    size_t feed_bytes = feed_samples * sizeof(int16_t);

    int16_t *mic = (int16_t *)malloc(mic_bytes);
    int16_t *feed = (int16_t *)malloc(feed_bytes);
    if (!mic || !feed) {
        ESP_LOGE(TAG, "WWE no mem (mic=%u feed=%u)", (unsigned)mic_bytes, (unsigned)feed_bytes);
        free(mic);
        free(feed);
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "WWE feed start: chunk=%d nch=%d", feed_chunksize, feed_nch);

    while (s_wwe_running) {
        if (is_suspended_now()) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }

        uint8_t *dst = (uint8_t *)mic;
        size_t got = 0;
        while (got < mic_bytes && s_wwe_running) {
            int r = audio_cap_read(dst + got, mic_bytes - got, 50);
            if (r < 0) {
                vTaskDelay(pdMS_TO_TICKS(10));
                continue;
            }
            if (r == 0) continue;
            got += (size_t)r;
        }
        if (!s_wwe_running) break;
        if (got < mic_bytes) continue;

        if (feed_nch == 1) {
            memcpy(feed, mic, mic_bytes);
        } else {
            for (int i = 0; i < feed_chunksize; i++) {
                feed[i * 2 + 0] = mic[i]; /* M */
                feed[i * 2 + 1] = 0;      /* R (no reference) */
            }
        }

        s_afe->feed(s_afe_data, feed);
    }

    free(mic);
    free(feed);
    s_feed_task = NULL;
    ESP_LOGI(TAG, "WWE feed stop");
    vTaskDelete(NULL);
}

static void wwe_fetch_task(void *arg)
{
    (void)arg;
    if (!s_afe || !s_afe_data) {
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "WWE fetch start");
    while (s_wwe_running) {
        if (is_suspended_now()) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }

        afe_fetch_result_t *res = s_afe->fetch(s_afe_data);
        if (!res) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }
        if (res->ret_value != ESP_OK) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }

        if (res->wakeup_state == WAKENET_DETECTED) {
            TickType_t now = xTaskGetTickCount();
            if (s_last_wake_tick != 0 && (now - s_last_wake_tick) < pdMS_TO_TICKS(1200)) {
                continue;
            }
            s_last_wake_tick = now;
            s_confidence = 100;
            s_last_src = WAKE_SRC_WWE;
            s_wake_pending = true;
            ESP_LOGI(TAG, "WWE wake detected");
        }
    }

    s_fetch_task = NULL;
    ESP_LOGI(TAG, "WWE fetch stop");
    vTaskDelete(NULL);
}

static int wwe_start(void)
{
    if (s_wwe_running) return 0;

    s_models = esp_srmodel_init("model");
    if (!s_models) {
        ESP_LOGE(TAG, "WWE esp_srmodel_init('model') failed (no model partition or models not flashed)");
        return -1;
    }

    // Use "MR" even for single-mic boards: feed M from mic, R as zeros.
    s_afe_cfg = afe_config_init("MR", s_models, AFE_TYPE_SR, AFE_MODE_LOW_COST);
    if (!s_afe_cfg) {
        ESP_LOGE(TAG, "WWE afe_config_init failed");
        return -1;
    }

    // Keep it minimal: wake-word only.
    s_afe_cfg->aec_init = false;
    s_afe_cfg->se_init = false;
    s_afe_cfg->ns_init = false;
    s_afe_cfg->vad_init = false;
    s_afe_cfg->wakenet_init = true;

    if (!s_afe_cfg->wakenet_model_name) {
        ESP_LOGE(TAG, "WWE no wakenet_model_name (select WakeNet model in menuconfig -> ESP Speech Recognition)");
        return -1;
    }

    {
        char *ww = esp_srmodel_get_wake_words(s_models, s_afe_cfg->wakenet_model_name);
        if (ww) {
            ESP_LOGI(TAG, "WWE wake words: %s", ww);
            free(ww);
        } else {
            ESP_LOGW(TAG, "WWE wake words: (unknown)");
        }
    }

    s_afe = esp_afe_handle_from_config(s_afe_cfg);
    if (!s_afe) {
        ESP_LOGE(TAG, "WWE esp_afe_handle_from_config failed");
        return -1;
    }

    s_afe_data = s_afe->create_from_config(s_afe_cfg);
    if (!s_afe_data) {
        ESP_LOGE(TAG, "WWE create_from_config failed");
        return -1;
    }

    s_wwe_running = true;
    BaseType_t rc1 = xTaskCreatePinnedToCore(wwe_feed_task, "wwe_feed", 8192, NULL, 5, &s_feed_task, 1);
    BaseType_t rc2 = xTaskCreatePinnedToCore(wwe_fetch_task, "wwe_fetch", 8192, NULL, 5, &s_fetch_task, 1);
    if (rc1 != pdPASS || rc2 != pdPASS) {
        ESP_LOGE(TAG, "WWE task create failed");
        s_wwe_running = false;
        return -1;
    }

    ESP_LOGI(TAG, "wake mode WWE (WakeNet/esp-sr), model=%s", s_afe_cfg->wakenet_model_name);
    return 0;
}

static void button_isr(void *arg)
{
    (void)arg;
    s_last_src = WAKE_SRC_BUTTON;
    s_wake_pending = true;
}

static bool poll_button(void)
{
    if (s_mode != WAKE_MODE_BUTTON && s_mode != WAKE_MODE_MULTI) return false;
    int gpio = CONFIG_WAKE_BUTTON_GPIO;
    int lvl = gpio_get_level(gpio);
#if defined(CONFIG_WAKE_BUTTON_ACTIVE_HIGH)
    return lvl != 0; /* pressed = high */
#else
    return lvl == 0; /* pressed = low */
#endif
}

static bool button_released(void)
{
    if (s_mode != WAKE_MODE_BUTTON && s_mode != WAKE_MODE_MULTI) return true;
    int gpio = CONFIG_WAKE_BUTTON_GPIO;
    int lvl = gpio_get_level(gpio);
#if defined(CONFIG_WAKE_BUTTON_ACTIVE_HIGH)
    return lvl == 0; /* released = low */
#else
    return lvl != 0; /* released = high */
#endif
}

static int button_init(void)
{
    int gpio = CONFIG_WAKE_BUTTON_GPIO;
#if defined(CONFIG_WAKE_BUTTON_ACTIVE_HIGH)
    gpio_config_t io = {
        .pin_bit_mask = (1ULL << gpio),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type = GPIO_INTR_POSEDGE,
    };
#else
    gpio_config_t io = {
        .pin_bit_mask = (1ULL << gpio),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_NEGEDGE,
    };
#endif
    esp_err_t err = gpio_config(&io);
    if (err) {
        ESP_LOGE(TAG, "gpio_config fail %d", err);
        return -1;
    }
    gpio_install_isr_service(0);
    gpio_isr_handler_add(gpio, button_isr, NULL);
    return 0;
}

int wake_init(wake_mode_t mode)
{
#if defined(CONFIG_WAKE_MODE_CMD)
    (void)mode;
    s_mode = WAKE_MODE_CMD;
#elif defined(CONFIG_WAKE_MODE_BUTTON)
    (void)mode;
    s_mode = WAKE_MODE_BUTTON;
#elif defined(CONFIG_WAKE_MODE_WWE)
    (void)mode;
    s_mode = WAKE_MODE_WWE;
#elif defined(CONFIG_WAKE_MODE_MULTI)
    (void)mode;
    s_mode = WAKE_MODE_MULTI;
#else
    (void)mode;
    s_mode = WAKE_MODE_RMS;
#endif
    s_wake_pending = false;
    s_confidence = 100;
    s_last_wake_tick = 0;
    s_button_armed = true;
    s_release_since_tick = 0;
    s_suspend_until_tick = 0;
    s_last_src = WAKE_SRC_NONE;

    if (s_mode == WAKE_MODE_CMD) {
        s_cmd_queue = xQueueCreate(CMD_QUEUE_LEN, CMD_MAX_LEN);
        if (!s_cmd_queue) {
            ESP_LOGE(TAG, "cmd queue create fail");
            return -1;
        }
        ESP_LOGI(TAG, "wake mode CMD (RX 'START')");
    } else if (s_mode == WAKE_MODE_BUTTON) {
        if (button_init() != 0) return -1;
        ESP_LOGI(TAG, "wake mode BUTTON, gpio=%d", CONFIG_WAKE_BUTTON_GPIO);
    } else if (s_mode == WAKE_MODE_WWE) {
        // WakeNet consumes audio via audio_cap_read() in a background task.
        // IMPORTANT: audio_cap must be started before wake_init() in this mode.
        if (wwe_start() != 0) {
            ESP_LOGE(TAG, "wake mode WWE init failed");
            return -1;
        }
    } else if (s_mode == WAKE_MODE_MULTI) {
        if (button_init() != 0) return -1;
        if (wwe_start() != 0) return -1;
        ESP_LOGI(TAG, "wake mode MULTI (BUTTON+WWE), gpio=%d", CONFIG_WAKE_BUTTON_GPIO);
    } else {
        ESP_LOGI(TAG, "wake mode RMS (stub: not implemented, use CMD)");
        /* RMS would need audio_cap to feed samples - deferred */
    }
    return 0;
}

bool wake_poll_or_wait(uint32_t timeout_ms)
{
    if (is_suspended_now()) {
        s_wake_pending = false;
        if (timeout_ms > 0) {
            TickType_t now = xTaskGetTickCount();
            TickType_t remain = (s_suspend_until_tick > now) ? (s_suspend_until_tick - now) : 0;
            TickType_t max_wait = pdMS_TO_TICKS(timeout_ms);
            TickType_t wait = (remain > 0 && remain < max_wait) ? remain : max_wait;
            if (wait > 0) vTaskDelay(wait);
        }
        return false;
    }

    if ((s_mode == WAKE_MODE_BUTTON || s_mode == WAKE_MODE_MULTI) && !s_button_armed) {
        // Re-arm only after stable release (avoids bounce triggering a new wake right after REC_END).
        TickType_t now = xTaskGetTickCount();
        if (button_released()) {
            if (s_release_since_tick == 0) s_release_since_tick = now;
            if ((now - s_release_since_tick) >= pdMS_TO_TICKS(BUTTON_REARM_RELEASE_MS)) {
                s_button_armed = true;
                s_release_since_tick = 0;
            }
        } else {
            s_release_since_tick = 0;
        }
    }
    if (s_wake_pending) {
        s_wake_pending = false;
        if ((s_mode == WAKE_MODE_BUTTON || s_mode == WAKE_MODE_MULTI) && !s_button_armed) return false;
        TickType_t now = xTaskGetTickCount();
        if (s_last_wake_tick != 0 && (now - s_last_wake_tick) < pdMS_TO_TICKS(200)) {
            return false;
        }
        s_last_wake_tick = now;
        if (s_mode == WAKE_MODE_BUTTON || s_mode == WAKE_MODE_MULTI) {
            s_button_armed = false;
            s_release_since_tick = 0;
        }
        if (s_last_src == WAKE_SRC_WWE) ESP_LOGI(TAG, "wake trigger: WWE");
        else if (s_last_src == WAKE_SRC_BUTTON) ESP_LOGI(TAG, "wake trigger: BUTTON");
        else if (s_last_src == WAKE_SRC_CMD) ESP_LOGI(TAG, "wake trigger: CMD");
        else ESP_LOGI(TAG, "wake trigger");
        return true;
    }
    if (s_mode == WAKE_MODE_CMD && s_cmd_queue) {
        char buf[CMD_MAX_LEN];
        if (xQueueReceive(s_cmd_queue, buf, pdMS_TO_TICKS(timeout_ms)) == pdTRUE) {
            if (strcmp(buf, "START") == 0 || strcmp(buf, "REC") == 0) {
                s_last_src = WAKE_SRC_CMD;
                return true;
            }
        }
    } else if (s_mode == WAKE_MODE_BUTTON || s_mode == WAKE_MODE_MULTI) {
        uint32_t elapsed = 0;
        while (elapsed < timeout_ms) {
            if (s_wake_pending) break; // e.g. WWE detected while we were waiting
            if (s_button_armed && poll_button()) {
                s_button_armed = false;
                TickType_t now = xTaskGetTickCount();
                s_last_wake_tick = now;
                s_release_since_tick = 0;
                s_last_src = WAKE_SRC_BUTTON;
                ESP_LOGI(TAG, "button trigger (poll)");
                return true;
            }
            if (!s_button_armed) {
                TickType_t now = xTaskGetTickCount();
                if (button_released()) {
                    if (s_release_since_tick == 0) s_release_since_tick = now;
                    if ((now - s_release_since_tick) >= pdMS_TO_TICKS(BUTTON_REARM_RELEASE_MS)) {
                        s_button_armed = true;
                        s_release_since_tick = 0;
                    }
                } else {
                    s_release_since_tick = 0;
                }
            }
            vTaskDelay(pdMS_TO_TICKS(25));
            elapsed += 25;
        }
    }
    return false;
}

uint8_t wake_get_confidence(void)
{
    return s_confidence;
}

void wake_on_rx_cmd(const char *cmd)
{
    if (!cmd || !s_cmd_queue || s_mode != WAKE_MODE_CMD) return;
    char buf[CMD_MAX_LEN];
    size_t n = strlen(cmd);
    if (n >= CMD_MAX_LEN) n = CMD_MAX_LEN - 1;
    memcpy(buf, cmd, n);
    buf[n] = '\0';
    xQueueSend(s_cmd_queue, buf, 0);
}
