#pragma once

#include "esp_err.h"
#include "driver/i2c_master.h"

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

#ifdef __cplusplus
}
#endif

