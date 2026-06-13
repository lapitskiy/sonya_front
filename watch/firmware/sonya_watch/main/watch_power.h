#pragma once

#include "esp_err.h"
#include <stdbool.h>

esp_err_t watch_power_enter_auto_off(void);
bool watch_power_usb_present(void);
