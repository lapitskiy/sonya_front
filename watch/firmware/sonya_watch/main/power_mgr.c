#include "power_mgr.h"

#include "esp_log.h"
#include "freertos/task.h"
#include "sonya_diaglog.h"

static const char *TAG = "power_mgr";

static TickType_t s_boot_tick = 0;
static TickType_t s_last_activity_tick = 0;
static TickType_t s_next_auto_off_attempt_tick = 0;
static TickType_t s_last_timeout_log_tick = 0;
static uint32_t s_idle_ms = 0;

void power_mgr_init(uint32_t idle_ms)
{
    s_idle_ms = idle_ms;
    s_boot_tick = xTaskGetTickCount();
    s_last_activity_tick = s_boot_tick;
    s_next_auto_off_attempt_tick = 0;
    s_last_timeout_log_tick = 0;
}

void power_mgr_mark_activity(const char *reason)
{
    s_last_activity_tick = xTaskGetTickCount();
    s_next_auto_off_attempt_tick = 0;
    ESP_LOGI(TAG, "activity (%s)", reason ? reason : "n/a");
}

bool power_mgr_auto_off_due(TickType_t now, bool recording, uint32_t *idle_ms_out)
{
    if (recording) return false;
    if (s_idle_ms == 0) return false;
    if (s_next_auto_off_attempt_tick != 0 &&
        (int32_t)(now - s_next_auto_off_attempt_tick) < 0) {
        return false;
    }

    const TickType_t timeout_ticks = pdMS_TO_TICKS(s_idle_ms);
    const TickType_t ref_tick = (s_last_activity_tick != 0) ? s_last_activity_tick : s_boot_tick;
    if (ref_tick == 0 || (now - ref_tick) < timeout_ticks) return false;

    uint32_t idle_ms = (uint32_t)((now - ref_tick) * portTICK_PERIOD_MS);
    if (idle_ms_out) *idle_ms_out = idle_ms;
    if (s_last_timeout_log_tick == 0 ||
        (now - s_last_timeout_log_tick) >= pdMS_TO_TICKS(5000)) {
        s_last_timeout_log_tick = now;
        ESP_LOGW(TAG, "timeout reached -> PMU off (idle_ms=%lu)", (unsigned long)idle_ms);
        sonya_diaglog_addf("sys", "auto_off idle_ms=%lu", (unsigned long)idle_ms);
    }
    return true;
}

void power_mgr_delay_auto_off_retry(uint32_t delay_ms, const char *reason)
{
    TickType_t now = xTaskGetTickCount();
    TickType_t delay_ticks = pdMS_TO_TICKS(delay_ms);
    if (delay_ticks == 0) delay_ticks = 1;
    s_next_auto_off_attempt_tick = now + delay_ticks;
    ESP_LOGW(TAG, "auto-off retry in %lums (%s)",
             (unsigned long)delay_ms, reason ? reason : "n/a");
}
