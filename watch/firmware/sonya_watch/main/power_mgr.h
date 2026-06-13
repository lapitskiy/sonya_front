#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"

void power_mgr_init(uint32_t idle_ms);
void power_mgr_mark_activity(const char *reason);

// Returns true once when the idle timeout is reached.
bool power_mgr_should_auto_off(TickType_t now, bool recording, bool link_connected, uint32_t *idle_ms_out);
