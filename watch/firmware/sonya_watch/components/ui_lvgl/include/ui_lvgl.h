#pragma once

#include <stdbool.h>
#include <stdint.h>

// Minimal LVGL UI wrapper, kept separate from main logic.
// It exposes the same "status_ui" concept: connection/recording/error and temporary messages.

int ui_lvgl_init(void);
void ui_lvgl_set_connected(bool connected);
void ui_lvgl_set_recording(bool recording);
void ui_lvgl_set_error(bool error);
void ui_lvgl_show_message(const char *msg, uint32_t ms);

