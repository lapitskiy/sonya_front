#pragma once

#include <stdbool.h>
#include <stdint.h>

void status_screen_init(void);
void status_screen_set_recording(bool recording);
void status_screen_set_error(bool error);

// Show a temporary on-screen message for [ms] milliseconds (non-blocking).
void status_screen_show_message(const char *msg, uint32_t ms);

