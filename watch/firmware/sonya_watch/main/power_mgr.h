#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"

void power_mgr_init(uint32_t idle_ms);
void power_mgr_mark_activity(const char *reason);

// Returns true while the idle timeout is reached and a power-off attempt is allowed.
bool power_mgr_auto_off_due(TickType_t now, bool recording, uint32_t *idle_ms_out);

// Delay the next power-off attempt without resetting real user activity.
void power_mgr_delay_auto_off_retry(uint32_t delay_ms, const char *reason);
