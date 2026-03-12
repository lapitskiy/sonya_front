#pragma once

#include "esp_err.h"
#include "driver/i2c_master.h"
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initialize shared I2C bus (I2C master driver).
 * Uses `CONFIG_I2C_SDA_GPIO` / `CONFIG_I2C_SCL_GPIO` from Kconfig.
 */
esp_err_t sonya_board_i2c_init(void);

/**
 * Get shared I2C bus handle. Initializes bus on first use.
 */
i2c_master_bus_handle_t sonya_board_i2c_bus(void);

/**
 * Try to configure board PMU to avoid automatic power-off on low-load state.
 * Current implementation targets AXP2101 at I2C address 0x34.
 */
esp_err_t sonya_board_pmu_init(void);

/**
 * Read PMU battery and power input status.
 */
esp_err_t sonya_board_pmu_read_status(int *batt_pct,
                                      uint16_t *batt_mv,
                                      uint16_t *vbus_mv,
                                      bool *charging,
                                      bool *vbus_in,
                                      bool *battery_present);

#ifdef __cplusplus
}
#endif

