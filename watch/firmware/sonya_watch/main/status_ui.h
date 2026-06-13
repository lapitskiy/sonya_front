#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <time.h>

void status_ui_init(void);

void status_ui_set_recording(bool recording);
void status_ui_set_error(bool error);
void status_ui_set_app_ready(bool ready);
void status_ui_set_time(time_t epoch, int16_t tz_offset_min);

// Show a temporary on-screen message for [ms] milliseconds (non-blocking).
void status_ui_show_message(const char *msg, uint32_t ms);

// Show an OK checkmark animation for [ms] milliseconds (non-blocking).
void status_ui_show_ok(uint32_t ms);

