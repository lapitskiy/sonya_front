#include "watch_power.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sonya_board.h"
#include "sonya_diaglog.h"
#include "soc/soc.h"
#include "soc/usb_serial_jtag_reg.h"

static const char *TAG = "watch_power";

static bool usb_serial_jtag_active(void)
{
    const uint32_t mask = USB_SERIAL_JTAG_SOF_INT_RAW |
                          USB_SERIAL_JTAG_USB_BUS_RESET_INT_RAW |
                          USB_SERIAL_JTAG_IN_TOKEN_REC_IN_EP1_INT_RAW;
    uint32_t before = REG_READ(USB_SERIAL_JTAG_INT_RAW_REG);

    // These bits are sticky. Clear them first, then wait for fresh USB traffic.
    REG_WRITE(USB_SERIAL_JTAG_INT_CLR_REG,
              USB_SERIAL_JTAG_SOF_INT_CLR |
              USB_SERIAL_JTAG_USB_BUS_RESET_INT_CLR |
              USB_SERIAL_JTAG_IN_TOKEN_REC_IN_EP1_INT_CLR);
    vTaskDelay(pdMS_TO_TICKS(25));

    uint32_t after = REG_READ(USB_SERIAL_JTAG_INT_RAW_REG);
    if (after & mask) {
        ESP_LOGI(TAG, "USB Serial/JTAG active raw=0x%08lx->0x%08lx",
                 (unsigned long)before, (unsigned long)after);
        return true;
    }
    return false;
}

bool watch_power_usb_present(void)
{
    if (usb_serial_jtag_active()) {
        return true;
    }

    int batt_pct = -1;
    uint16_t batt_mv = 0;
    uint16_t vbus_mv = 0;
    bool charging = false;
    bool vbus_in = false;
    bool battery_present = false;
    esp_err_t err = sonya_board_pmu_read_status(&batt_pct, &batt_mv, &vbus_mv, &charging, &vbus_in, &battery_present);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "USB/VBUS status read failed: %d", (int)err);
        return false;
    }
    if (vbus_in || vbus_mv > 3500) {
        ESP_LOGI(TAG, "PMU VBUS active vbus_in=%d vbus_mv=%u", vbus_in ? 1 : 0, (unsigned)vbus_mv);
        return true;
    }
    return false;
}

esp_err_t watch_power_enter_auto_off(void)
{
    if (watch_power_usb_present()) {
        ESP_LOGW(TAG, "USB/VBUS present -> skip PMU power off");
        sonya_diaglog_add("sys", "auto_power_off skip=vbus");
        return ESP_ERR_NOT_SUPPORTED;
    }

    ESP_LOGW(TAG, "entering PMU power off");
    sonya_diaglog_add("sys", "auto_power_off");
    vTaskDelay(pdMS_TO_TICKS(30));

    esp_err_t err = sonya_board_power_off();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PMU power off failed: %d", (int)err);
        sonya_diaglog_addf("sys", "auto_power_off fail=%d", (int)err);
        return err;
    }

    vTaskDelay(pdMS_TO_TICKS(200));
    ESP_LOGE(TAG, "PMU power off returned but device still running");
    return ESP_FAIL;
}
