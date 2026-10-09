#include "watch_idle_off.h"

#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "link_state.h"
#include "power_mgr.h"
#include "sonya_ble.h"
#include "sonya_diaglog.h"
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
    if (audio_streaming && stop_audio) {
        stop_audio(stop_audio_arg);
    }

    // Actually power off FIRST. The BLE "we're going down" notify + conn-param renegotiation
    // used to run *before* this and could stall for up to ~2s (or longer under NimBLE mbuf
    // pressure) while a phone was connected, which meant this code never even reached the PMU
    // call -> the watch silently stayed on. We only need BLE telemetry if power-off *fails*
    // (we're staying alive); if it succeeds, the radio dies with the rest of the board anyway.
    esp_err_t off_err = watch_power_enter_auto_off();
    if (off_err != ESP_OK) {
        ESP_LOGW(TAG, "idle auto-off failed err=%d -> retry", (int)off_err);
        if (link_state_is_connected()) {
            sonya_ble_send_evt_error("AUTO_POWEROFF:IDLE");
        }
        status_ui_set_error(true);
        power_mgr_delay_auto_off_retry(3000, "PMU_FAIL");
    }
}

void watch_idle_off_force(const char *reason,
                          bool recording,
                          bool audio_streaming,
                          watch_idle_off_stop_audio_fn_t stop_audio,
                          void *stop_audio_arg)
{
    const char *r = reason ? reason : "?";

    if (recording) {
        ESP_LOGW(TAG, "forced power-off ignored: recording in progress (%s)", r);
        return;
    }

    if (watch_power_usb_present()) {
        ESP_LOGW(TAG, "forced power-off blocked by live USB/VBUS (%s)", r);
        status_ui_show_message("USB", 700);
        return;
    }

    ESP_LOGW(TAG, "forced power-off start (%s) link=%s audio=%d",
             r, link_state_name(link_state_get()), audio_streaming ? 1 : 0);
    sonya_diaglog_addf("sys", "forced_off reason=%s", r);
    status_ui_show_message("OFF", 700);
    if (audio_streaming && stop_audio) {
        stop_audio(stop_audio_arg);
    }

    // Same reasoning as watch_idle_off_tick(): power off FIRST, don't let a BLE notify/
    // conn-param round trip to a connected phone gate (and potentially stall) the actual
    // shutdown. This is exactly what was breaking double-press-to-power-off while the phone
    // was connected: the BLE call never returned, so watch_power_enter_auto_off() was never
    // reached, and the "OFF" message simply expired back to "READY" with nothing powered off.
    esp_err_t off_err = watch_power_enter_auto_off();
    if (off_err != ESP_OK) {
        ESP_LOGW(TAG, "forced power-off failed err=%d", (int)off_err);
        if (link_state_is_connected()) {
            sonya_ble_send_evt_error("POWEROFF:BUTTON");
        }
        status_ui_set_error(true);
    }
}
