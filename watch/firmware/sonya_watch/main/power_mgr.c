#include "power_mgr.h"

#include "esp_log.h"
#include "freertos/task.h"
#include "sonya_diaglog.h"

static const char *TAG = "power_mgr";

static TickType_t s_boot_tick = 0;
static TickType_t s_last_activity_tick = 0;
static uint32_t s_idle_ms = 0;
static bool s_auto_off_attempted = false;

void power_mgr_init(uint32_t idle_ms)
{
    s_idle_ms = idle_ms;
    s_boot_tick = xTaskGetTickCount();
    s_last_activity_tick = s_boot_tick;
    s_auto_off_attempted = false;
}

void power_mgr_mark_activity(const char *reason)
{
    s_last_activity_tick = xTaskGetTickCount();
    s_auto_off_attempted = false;
    ESP_LOGI(TAG, "activity (%s)", reason ? reason : "n/a");
}

bool power_mgr_should_auto_off(TickType_t now, bool recording, bool link_connected, uint32_t *idle_ms_out)
{
    if (recording) return false;
    if (link_connected) {
        s_auto_off_attempted = false;
        return false;
    }
    if (s_auto_off_attempted) return false;

    const TickType_t timeout_ticks = pdMS_TO_TICKS(s_idle_ms);
    const TickType_t ref_tick = (s_last_activity_tick != 0) ? s_last_activity_tick : s_boot_tick;
    if (ref_tick == 0 || (now - ref_tick) < timeout_ticks) return false;

    s_auto_off_attempted = true;
    uint32_t idle_ms = (uint32_t)((now - ref_tick) * portTICK_PERIOD_MS);
    if (idle_ms_out) *idle_ms_out = idle_ms;
    ESP_LOGW(TAG, "timeout reached -> PMU off (idle_ms=%lu)", (unsigned long)idle_ms);
    sonya_diaglog_addf("sys", "auto_off idle_ms=%lu", (unsigned long)idle_ms);
    return true;
}
