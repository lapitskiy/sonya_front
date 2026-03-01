#include "sonya_board.h"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_check.h"
#include "driver/i2c_master.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define XPOWERS_CHIP_AXP2101
#include "XPowersLib.h"

static const char *TAG = "sonya_pmu";

static constexpr uint8_t AXP2101_ADDR = 0x34;

static int pmu_register_read(uint8_t devAddr, uint8_t regAddr, uint8_t *data, uint8_t len)
{
    i2c_master_bus_handle_t bus = sonya_board_i2c_bus();
    if (!bus) return -1;

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = devAddr,
        .scl_speed_hz = 400000,
        .scl_wait_us = 0,
        .flags = {},
    };
    i2c_master_dev_handle_t dev;
    esp_err_t err = i2c_master_bus_add_device(bus, &dev_cfg, &dev);
    if (err != ESP_OK) return (int)err;

    err = i2c_master_transmit_receive(dev, &regAddr, 1, data, len, 50);
    i2c_master_bus_rm_device(dev);
    return (err == ESP_OK) ? 0 : (int)err;
}

static int pmu_register_write(uint8_t devAddr, uint8_t regAddr, uint8_t *data, uint8_t len)
{
    i2c_master_bus_handle_t bus = sonya_board_i2c_bus();
    if (!bus) return -1;

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = devAddr,
        .scl_speed_hz = 400000,
        .scl_wait_us = 0,
        .flags = {},
    };
    i2c_master_dev_handle_t dev;
    esp_err_t err = i2c_master_bus_add_device(bus, &dev_cfg, &dev);
    if (err != ESP_OK) return (int)err;

    // TX: reg + payload
    uint8_t buf[1 + 16];
    if (len > 16) {
        i2c_master_bus_rm_device(dev);
        return -1;
    }
    buf[0] = regAddr;
    for (int i = 0; i < (int)len; i++) buf[1 + i] = data[i];
    err = i2c_master_transmit(dev, buf, 1 + len, 50);
    i2c_master_bus_rm_device(dev);
    return (err == ESP_OK) ? 0 : (int)err;
}

extern "C" esp_err_t sonya_board_pmu_init(void)
{
    ESP_RETURN_ON_ERROR(sonya_board_i2c_init(), TAG, "i2c_init");

    // Quick probe first
    esp_err_t err = i2c_master_probe(sonya_board_i2c_bus(), AXP2101_ADDR, 50);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "AXP2101 not found (0x%02X): %s", AXP2101_ADDR, esp_err_to_name(err));
        return err;
    }

    XPowersPMU pmu;
    if (!pmu.begin(AXP2101_ADDR, pmu_register_read, pmu_register_write)) {
        ESP_LOGE(TAG, "PMU.begin failed");
        return ESP_FAIL;
    }

    // Don’t disable anything here: only force-enable rails that are commonly needed by display.
    // Board schematic rails: 3.3V + 1.8V + 2.8V are typically required by AMOLED + touch.
    pmu.setDC1Voltage(3300);
    pmu.enableDC1();

    pmu.setALDO4Voltage(1800);
    pmu.enableALDO4();

    pmu.setBLDO2Voltage(2800);
    pmu.enableBLDO2();

    // Charging stability on boards without TS thermistor
    pmu.disableTSPinMeasure();

    ESP_LOGI(TAG, "PMU ok: DC1=%d ALDO4=%d BLDO2=%d batt=%d%%",
             pmu.isEnableDC1(), pmu.isEnableALDO4(), pmu.isEnableBLDO2(), pmu.getBatteryPercent());
    // Give power rails time to stabilize before LCD init
    vTaskDelay(pdMS_TO_TICKS(150));
    return ESP_OK;
}

