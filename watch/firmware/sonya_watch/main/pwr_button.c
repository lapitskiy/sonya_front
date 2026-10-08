#include "pwr_button.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "pwr_button";

// Two short PWR presses within this window request a forced power-off, so the user doesn't
// have to hold the button for the hardware's 3-5s long-press-off.
#define PWR_BUTTON_DOUBLE_PRESS_WINDOW_MS 600U

static TickType_t s_first_press_tick = 0;

void pwr_button_init(void)
{
    s_first_press_tick = 0;
}

bool pwr_button_tick(bool short_press_event)
{
    if (!short_press_event) {
        return false;
    }

    const TickType_t now = xTaskGetTickCount();
    if (s_first_press_tick != 0 &&
        (int32_t)(now - s_first_press_tick) <= (int32_t)pdMS_TO_TICKS(PWR_BUTTON_DOUBLE_PRESS_WINDOW_MS)) {
        ESP_LOGW(TAG, "double short press detected");
        s_first_press_tick = 0;
        return true;
    }

    s_first_press_tick = now;
    return false;
}
