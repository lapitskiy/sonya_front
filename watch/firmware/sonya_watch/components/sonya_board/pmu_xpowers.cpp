#include "sonya_board.h"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_check.h"
#include "driver/i2c_master.h"
#include "sonya_diaglog.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define XPOWERS_CHIP_AXP2101
#include "XPowersLib.h"

static const char *TAG = "sonya_pmu";

static constexpr uint8_t AXP2101_ADDR = 0x34;
static XPowersPMU s_pmu;
static bool s_pmu_ready = false;

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

    if (!s_pmu.begin(AXP2101_ADDR, pmu_register_read, pmu_register_write)) {
        ESP_LOGE(TAG, "PMU.begin failed");
        return ESP_FAIL;
    }
    s_pmu_ready = true;

    // Don’t disable anything here: only force-enable rails that are commonly needed by display.
    // Board schematic rails: 3.3V + 1.8V + 2.8V are typically required by AMOLED + touch.
    s_pmu.setDC1Voltage(3300);
    s_pmu.enableDC1();

    s_pmu.setALDO4Voltage(1800);
    s_pmu.enableALDO4();

    s_pmu.setBLDO2Voltage(2800);
    s_pmu.enableBLDO2();

    // Charging stability on boards without TS thermistor
    s_pmu.disableTSPinMeasure();

    // Enable ADC channels so we can read voltages reliably
    s_pmu.enableGeneralAdcChannel();
    s_pmu.enableVbusVoltageMeasure();
    s_pmu.enableBattVoltageMeasure();

    const int batt_pct = s_pmu.getBatteryPercent();
    const bool vbus_in = s_pmu.isVbusIn();
    const bool charging = s_pmu.isCharging();
    const uint16_t vbus_mv = s_pmu.getVbusVoltage();   // 0 if no VBUS
    const uint16_t batt_mv = s_pmu.getBattVoltage();   // 0 if no battery
    const bool vbus_good = s_pmu.isVbusGood();
    const bool discharging = s_pmu.isDischarge();
    const bool standby = s_pmu.isStandby();
    const int chg_status = (int)s_pmu.getChargerStatus();
    const int pwr_off_src = (int)s_pmu.getPowerOffSource();
    const bool off_vsys_uv = s_pmu.isVsysUnderVoltageOffSource();
    const bool off_pwron_low = s_pmu.isPwronAlwaysLowOffSource();
    const bool off_sw = s_pmu.isSwConfigOffSource();
    const bool off_pwron_pulldown = s_pmu.isPwrSourcePullDown();

    ESP_LOGI(TAG, "PMU ok: DC1=%d ALDO4=%d BLDO2=%d batt=%d%%",
             s_pmu.isEnableDC1(), s_pmu.isEnableALDO4(), s_pmu.isEnableBLDO2(), batt_pct);
    ESP_LOGI(TAG, "PMU diag: vbus_in=%d charging=%d vbus_mv=%u batt_mv=%u",
             (int)vbus_in, (int)charging, (unsigned)vbus_mv, (unsigned)batt_mv);
    ESP_LOGI(TAG, "PMU diag2: vbus_good=%d discharging=%d standby=%d chg_status=%d",
             (int)vbus_good, (int)discharging, (int)standby, chg_status);
    ESP_LOGI(TAG, "PMU offsrc: raw=%d vsys_uv=%d pwron_always_low=%d sw=%d pwron_pulldown=%d",
             pwr_off_src, (int)off_vsys_uv, (int)off_pwron_low, (int)off_sw, (int)off_pwron_pulldown);

    // Persist a short snapshot for post-mortem.
    sonya_diaglog_addf("pmu", "batt=%d%% %umV vbus=%umV chg=%d st=%d off=%d",
                       batt_pct, (unsigned)batt_mv, (unsigned)vbus_mv, (int)charging, chg_status, pwr_off_src);
    sonya_diaglog_addf("pmu", "offsrc raw=%d vsys_uv=%d pwron_low=%d sw=%d",
                       pwr_off_src, (int)off_vsys_uv, (int)off_pwron_low, (int)off_sw);
    // Give power rails time to stabilize before LCD init
    vTaskDelay(pdMS_TO_TICKS(150));
    return ESP_OK;
}

extern "C" esp_err_t sonya_board_pmu_read_status(int *batt_pct,
                                                  uint16_t *batt_mv,
                                                  uint16_t *vbus_mv,
                                                  bool *charging,
                                                  bool *vbus_in,
                                                  bool *battery_present)
{
    if (!s_pmu_ready) {
        return ESP_ERR_INVALID_STATE;
    }
    if (!batt_pct || !batt_mv || !vbus_mv || !charging || !vbus_in || !battery_present) {
        return ESP_ERR_INVALID_ARG;
    }

    *battery_present = s_pmu.isBatteryConnect();
    *batt_pct = s_pmu.getBatteryPercent();
    *batt_mv = s_pmu.getBattVoltage();
    *vbus_mv = s_pmu.getVbusVoltage();
    *charging = s_pmu.isCharging();
    *vbus_in = s_pmu.isVbusIn();
    return ESP_OK;
}

