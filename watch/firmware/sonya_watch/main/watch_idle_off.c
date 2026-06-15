#include "watch_idle_off.h"

#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "link_state.h"
#include "power_mgr.h"
#include "sonya_ble.h"
#include "status_ui.h"
#include "watch_power.h"

static const char *TAG = "watch_idle_off";

void watch_idle_off_tick(bool recording,
                         bool audio_streaming,
                         watch_idle_off_stop_audio_fn_t stop_audio,
                         void *stop_audio_arg)
{
    uint32_t idle_ms = 0;
    if (!power_mgr_auto_off_due(xTaskGetTickCount(), recording, &idle_ms)) {
        return;
    }

    if (watch_power_usb_present()) {
        ESP_LOGW(TAG, "idle auto-off blocked by live USB/VBUS (idle_ms=%lu)",
                 (unsigned long)idle_ms);
        power_mgr_delay_auto_off_retry(1000, "USB_POWER");
        return;
    }

    ESP_LOGW(TAG, "idle auto-off start idle_ms=%lu link=%s audio=%d",
             (unsigned long)idle_ms, link_state_name(link_state_get()), audio_streaming ? 1 : 0);
    status_ui_show_message("OFF", 700);
    if (link_state_is_connected()) {
        sonya_ble_send_evt_error("AUTO_POWEROFF:IDLE");
    }
    if (audio_streaming && stop_audio) {
        stop_audio(stop_audio_arg);
    }
    (void)sonya_ble_set_conn_power_save(true);
    vTaskDelay(pdMS_TO_TICKS(60));

    esp_err_t off_err = watch_power_enter_auto_off();
    if (off_err != ESP_OK) {
        ESP_LOGW(TAG, "idle auto-off failed err=%d -> retry", (int)off_err);
        status_ui_set_error(true);
        power_mgr_delay_auto_off_retry(3000, "PMU_FAIL");
    }
}
