#pragma once

#include <stdbool.h>
#include <stdint.h>

void status_ui_init(void);

void status_ui_set_recording(bool recording);
void status_ui_set_error(bool error);

// Show a temporary on-screen message for [ms] milliseconds (non-blocking).
void status_ui_show_message(const char *msg, uint32_t ms);

// Show an OK checkmark animation for [ms] milliseconds (non-blocking).
void status_ui_show_ok(uint32_t ms);

