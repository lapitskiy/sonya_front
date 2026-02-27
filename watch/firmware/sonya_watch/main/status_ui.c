#include "status_ui.h"

#include "sonya_ble.h"
#include "status_screen.h"
#include "ui_lvgl.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"

static const char *TAG = "status_ui";

static int s_gpio = -1;
static int s_active = 1;

static volatile bool s_recording = false;
static volatile bool s_error = false;

static void task_ui_conn(void *arg)
{
    (void)arg;
    bool last = false;
    for (;;) {
        bool conn = sonya_ble_is_connected();
        if (conn != last) {
            ui_lvgl_set_connected(conn);
            last = conn;
        }
        vTaskDelay(pdMS_TO_TICKS(250));
    }
}

static inline void led_write(bool on)
{
    if (s_gpio < 0) return;
    int level = on ? s_active : (1 - s_active);
    gpio_set_level((gpio_num_t)s_gpio, level);
}

void status_ui_set_recording(bool recording)
{
    static bool last = false;
    s_recording = recording;
    if (CONFIG_UI_LVGL_ENABLE) ui_lvgl_set_recording(recording);
    else status_screen_set_recording(recording);
    if (recording != last) {
        ESP_LOGI(TAG, "recording=%d", recording ? 1 : 0);
        last = recording;
    }
}

void status_ui_set_error(bool error)
{
    static bool last = false;
    s_error = error;
    if (CONFIG_UI_LVGL_ENABLE) ui_lvgl_set_error(error);
    else status_screen_set_error(error);
    if (error != last) {
        ESP_LOGI(TAG, "error=%d", error ? 1 : 0);
        last = error;
    }
}

void status_ui_show_message(const char *msg, uint32_t ms)
{
    if (CONFIG_UI_LVGL_ENABLE) ui_lvgl_show_message(msg, ms);
    else status_screen_show_message(msg, ms);
}

void status_ui_show_ok(uint32_t ms)
{
    if (CONFIG_UI_LVGL_ENABLE) ui_lvgl_show_ok(ms);
    else status_screen_show_message("OK", ms);
}

static void task_led(void *arg)
{
    (void)arg;

    for (;;) {
        if (s_gpio < 0) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }

        if (s_error) {
            // Triple-blink burst, then pause.
            for (int i = 0; i < 3; i++) {
                led_write(true);
                vTaskDelay(pdMS_TO_TICKS(120));
                led_write(false);
                vTaskDelay(pdMS_TO_TICKS(120));
            }
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }

        bool conn = sonya_ble_is_connected();
        if (s_recording) {
            // Fast blink while recording.
            led_write(true);
            vTaskDelay(pdMS_TO_TICKS(100));
            led_write(false);
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        if (conn) {
            // Solid ON when connected.
            led_write(true);
            vTaskDelay(pdMS_TO_TICKS(250));
            continue;
        }

        // Slow blink while advertising (not connected).
        led_write(true);
        vTaskDelay(pdMS_TO_TICKS(200));
        led_write(false);
        vTaskDelay(pdMS_TO_TICKS(1800));
    }
}

void status_ui_init(void)
{
#if CONFIG_STATUS_LED_GPIO
    s_gpio = CONFIG_STATUS_LED_GPIO;
#else
    s_gpio = -1;
#endif

    s_active = CONFIG_STATUS_LED_ACTIVE_LEVEL;

    if (s_gpio < 0) {
        ESP_LOGI(TAG, "status LED disabled (CONFIG_STATUS_LED_GPIO=-1)");
        // Still allow status screen.
    }

    if (s_gpio >= 0) {
        ESP_LOGI(TAG, "status LED: gpio=%d active=%d", s_gpio, s_active);
        gpio_reset_pin((gpio_num_t)s_gpio);
        gpio_set_direction((gpio_num_t)s_gpio, GPIO_MODE_OUTPUT);
        led_write(false);

        xTaskCreate(task_led, "status_led", 2048, NULL, 5, NULL);
    }

    if (CONFIG_UI_LVGL_ENABLE) {
        int rc = ui_lvgl_init();
        if (rc != 0) {
            ESP_LOGE(TAG, "ui_lvgl_init failed (%d)", rc);
            // No fallback: keep running headless, signal error via LED task (if configured).
            s_error = true;
            led_write(false);
            return;
        } else {
            xTaskCreate(task_ui_conn, "ui_conn", 2048, NULL, 5, NULL);
        }
    } else {
        status_screen_init();
    }
}

