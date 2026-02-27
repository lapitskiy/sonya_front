#pragma once

#include <stdbool.h>

#include "lvgl.h"

/**
 * Internal GIF helper for ui_lvgl.
 * Must be called/used with LVGL lock held.
 */

bool ui_lvgl_gif_create(lv_obj_t *parent);

void ui_lvgl_gif_hide_rec(void);
void ui_lvgl_gif_hide_done(void);
void ui_lvgl_gif_hide_all(void);

bool ui_lvgl_gif_ensure_loaded_rec(void);
bool ui_lvgl_gif_ensure_loaded_done(void);

void ui_lvgl_gif_show_rec(void);
void ui_lvgl_gif_show_done(void);

lv_obj_t * ui_lvgl_gif_obj_rec(void);
lv_obj_t * ui_lvgl_gif_obj_done(void);

