#include "sonya_board.h"

#include "sdkconfig.h"
#include "esp_check.h"
#include "esp_log.h"
#include "driver/i2c_master.h"

static const char *TAG = "sonya_board";

static i2c_master_bus_handle_t s_i2c_bus = NULL;
static bool s_i2c_inited = false;


esp_err_t sonya_board_i2c_init(void)
{
    if (s_i2c_inited) return ESP_OK;

    i2c_master_bus_config_t conf = {
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .i2c_port = I2C_NUM_0,
        .sda_io_num = (gpio_num_t)CONFIG_I2C_SDA_GPIO,
        .scl_io_num = (gpio_num_t)CONFIG_I2C_SCL_GPIO,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .flags = {
            .enable_internal_pullup = 1,
        },
    };

    ESP_RETURN_ON_ERROR(i2c_new_master_bus(&conf, &s_i2c_bus), TAG, "i2c_new_master_bus");
    s_i2c_inited = true;
    ESP_LOGI(TAG, "I2C bus init ok: SDA=%d SCL=%d", CONFIG_I2C_SDA_GPIO, CONFIG_I2C_SCL_GPIO);
    return ESP_OK;
}

i2c_master_bus_handle_t sonya_board_i2c_bus(void)
{
    if (!s_i2c_inited) {
        (void)sonya_board_i2c_init();
    }
    return s_i2c_bus;
}

