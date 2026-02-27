/**
 * @file ui_lvgl.c
 * @brief LVGL UI wrapper (isolated module)
 *
 * Goal: keep UI separate from wake/rec/ble logic.
 * The rest of firmware can keep calling status_ui_*; status_ui will route to this module.
 */

#include "ui_lvgl.h"

#include <string.h>

#include "esp_log.h"
#include "esp_check.h"
#include "esp_heap_caps.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_sh8601.h"
#include "esp_lcd_touch_ft5x06.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"

// LVGL port
#include "esp_lvgl_port.h"
#include "lvgl.h"
#include "sonya_board.h"

static const char *TAG = "ui_lvgl";

// Waveshare ESP32-S3 Touch AMOLED 2.06 (QSPI) pinout (same as status_screen.c)
#define LCD_SPI_HOST      SPI2_HOST
#define LCD_PIN_CS        12
#define LCD_PIN_PCLK      11
#define LCD_PIN_DATA0     4
#define LCD_PIN_DATA1     5
#define LCD_PIN_DATA2     6
#define LCD_PIN_DATA3     7
#define LCD_PIN_RST       8

#define LCD_H_RES         410
#define LCD_V_RES         502
#define LCD_X_GAP         0x16
#define LCD_Y_GAP         0

// Touch (FT3168 via ft5x06 driver family)
#define TOUCH_PIN_RST     9
#define TOUCH_PIN_INT     38

// UI margins (percent-based, integer math)
#define UI_X_PAD          8
#define UI_X_SHIFT_10P    ((LCD_H_RES * 10) / 100)  // ~10% of width
#define UI_TOP_Y_3P       ((LCD_V_RES * 3) / 100)   // ~3% of height

static esp_lcd_panel_handle_t s_panel = NULL;
static esp_lcd_panel_io_handle_t s_io = NULL;
static lv_display_t *s_disp = NULL;
static esp_lcd_touch_handle_t s_touch = NULL;
static lv_indev_t *s_indev = NULL;

// Init commands known to work on Waveshare AMOLED SH8601 (copied from status_screen.c)
static const sh8601_lcd_init_cmd_t lcd_init_cmds[] = {
    {0x11, (uint8_t[]){0x00}, 0, 120},                 // Sleep out
    {0xC4, (uint8_t[]){0x80}, 1, 0},
    {0x44, (uint8_t[]){0x01, 0xD1}, 2, 0},
    {0x35, (uint8_t[]){0x00}, 1, 0},                   // TE on (param)
    {0x53, (uint8_t[]){0x20}, 1, 10},
    {0x63, (uint8_t[]){0xFF}, 1, 10},
    {0x51, (uint8_t[]){0x00}, 1, 10},                  // Brightness 0 (will be set to 0xFF later)
    {0x2A, (uint8_t[]){0x00, 0x16, 0x01, 0xAF}, 4, 0},  // Column address set (0x16..0x1AF)
    {0x2B, (uint8_t[]){0x00, 0x00, 0x01, 0xF5}, 4, 0},  // Row address set (0..0x1F5)
    {0x29, (uint8_t[]){0x00}, 0, 10},                  // Display on
    {0x51, (uint8_t[]){0xFF}, 1, 0},                   // Brightness max
};

static lv_obj_t *s_label = NULL;
static lv_obj_t *s_state = NULL;
static lv_obj_t *s_bt = NULL;
static lv_obj_t *s_bat = NULL;
static lv_obj_t *s_spinner = NULL;
static lv_obj_t *s_ok = NULL;

static volatile bool s_connected = false;
static volatile bool s_recording = false;
static volatile bool s_error = false;

static lv_timer_t *s_restore_timer = NULL;

static void ok_set_opa(void *obj, int32_t v)
{
    if (!obj) return;
    if (v < 0) v = 0;
    if (v > 255) v = 255;
    lv_obj_set_style_opa((lv_obj_t *)obj, (lv_opa_t)v, LV_PART_MAIN);
}

typedef enum {
    UI_EVT_RECORDING = 1,
} ui_evt_type_t;

typedef struct {
    uint8_t type;
    uint8_t val;
    uint32_t tick_posted;
} ui_evt_t;

static QueueHandle_t s_evt_q = NULL;
static TaskHandle_t s_evt_task = NULL;

// Embedded PNGs (via EMBED_FILES in component CMakeLists.txt)
extern const uint8_t bluetooh_off_24_png_start[] asm("_binary_bluetooh_off_24_png_start");
extern const uint8_t bluetooh_off_24_png_end[]   asm("_binary_bluetooh_off_24_png_end");
extern const uint8_t bluetooh_on_24_png_start[]  asm("_binary_bluetooh_on_24_png_start");
extern const uint8_t bluetooh_on_24_png_end[]    asm("_binary_bluetooh_on_24_png_end");

static bool s_bt_imgs_inited = false;
static lv_img_dsc_t s_img_bt_off;
static lv_img_dsc_t s_img_bt_on;

static void diag_dump_heap(const char *where)
{
    uint32_t free_int = (uint32_t)heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
    uint32_t free_8b  = (uint32_t)heap_caps_get_free_size(MALLOC_CAP_8BIT);
    uint32_t free_dma = (uint32_t)heap_caps_get_free_size(MALLOC_CAP_DMA);
    ESP_LOGI(TAG, "[diag] heap %s: internal=%u, dma=%u, 8bit=%u",
             where ? where : "?", (unsigned)free_int, (unsigned)free_dma, (unsigned)free_8b);
}

static void set_label_text(const char *text)
{
    if (!s_label) return;
    lv_label_set_text(s_label, text ? text : "");
}

static void refresh_state(void)
{
    if (!s_state) return;
    const char *txt = "";
    if (s_error) txt = "ERR";
    else if (s_recording) txt = "REC";
    else if (s_connected) txt = "BLE";
    else txt = "ADV";
    lv_label_set_text(s_state, txt);
}

static void refresh_top_icons(void)
{
    if (s_bt) {
        if (!s_bt_imgs_inited) {
            memset(&s_img_bt_off, 0, sizeof(s_img_bt_off));
            s_img_bt_off.header.magic = LV_IMAGE_HEADER_MAGIC;
            s_img_bt_off.header.cf = LV_COLOR_FORMAT_RAW;
            s_img_bt_off.header.w = 24;
            s_img_bt_off.header.h = 24;
            s_img_bt_off.header.stride = 0;
            s_img_bt_off.data = bluetooh_off_24_png_start;
            s_img_bt_off.data_size = (uint32_t)(bluetooh_off_24_png_end - bluetooh_off_24_png_start);

            memset(&s_img_bt_on, 0, sizeof(s_img_bt_on));
            s_img_bt_on.header.magic = LV_IMAGE_HEADER_MAGIC;
            s_img_bt_on.header.cf = LV_COLOR_FORMAT_RAW;
            s_img_bt_on.header.w = 24;
            s_img_bt_on.header.h = 24;
            s_img_bt_on.header.stride = 0;
            s_img_bt_on.data = bluetooh_on_24_png_start;
            s_img_bt_on.data_size = (uint32_t)(bluetooh_on_24_png_end - bluetooh_on_24_png_start);

            s_bt_imgs_inited = true;
        }

        lv_image_set_src(s_bt, s_connected ? (const void *)&s_img_bt_on : (const void *)&s_img_bt_off);
    }

    if (s_bat) {
        // Battery measurement is not wired yet in firmware; show explicit N/A.
        lv_label_set_text(s_bat, LV_SYMBOL_BATTERY_FULL " N/A");
        lv_obj_set_style_text_color(s_bat, lv_color_make(0xC0, 0xC0, 0xC0), 0);
    }
}

static void restore_timer_cb(lv_timer_t *t)
{
    (void)t;
    if (s_spinner) lv_obj_add_flag(s_spinner, LV_OBJ_FLAG_HIDDEN);
    if (s_ok) lv_obj_add_flag(s_ok, LV_OBJ_FLAG_HIDDEN);
    if (!s_recording && s_label) lv_obj_clear_flag(s_label, LV_OBJ_FLAG_HIDDEN);
    set_label_text("SONYA");
    // one-shot
    if (s_restore_timer) {
        lv_timer_del(s_restore_timer);
        s_restore_timer = NULL;
    }
}

static void ui_apply_recording_pre(void)
{
    lvgl_port_lock(0);
    if (s_restore_timer) {
        lv_timer_del(s_restore_timer);
        s_restore_timer = NULL;
    }
    if (s_label) lv_obj_add_flag(s_label, LV_OBJ_FLAG_HIDDEN);
    if (s_spinner) {
        lv_obj_clear_flag(s_spinner, LV_OBJ_FLAG_HIDDEN);
        lv_obj_move_foreground(s_spinner);
    }
    refresh_state();
    refresh_top_icons();
    lvgl_port_unlock();
}

static void ui_apply_recording_stop(bool was)
{
    lvgl_port_lock(0);
    if (s_spinner) lv_obj_add_flag(s_spinner, LV_OBJ_FLAG_HIDDEN);
    if (was) {
        if (s_restore_timer) {
            lv_timer_del(s_restore_timer);
            s_restore_timer = NULL;
        }
        s_restore_timer = lv_timer_create(restore_timer_cb, 900, NULL);
        if (s_label) {
            lv_obj_clear_flag(s_label, LV_OBJ_FLAG_HIDDEN);
            set_label_text(LV_SYMBOL_OK " DONE");
        }
    } else if (s_label) {
        lv_obj_clear_flag(s_label, LV_OBJ_FLAG_HIDDEN);
        set_label_text("SONYA");
    }
    refresh_state();
    refresh_top_icons();
    lvgl_port_unlock();
}

static void ui_evt_task(void *arg)
{
    (void)arg;
    ui_evt_t evt;

    for (;;) {
        if (xQueueReceive(s_evt_q, &evt, portMAX_DELAY) != pdTRUE) continue;
        if (evt.type != UI_EVT_RECORDING) continue;

        uint32_t now = (uint32_t)xTaskGetTickCount();
        uint32_t lag = now - evt.tick_posted;
        ESP_LOGI(TAG, "[ui_evt] recording=%d lag=%lums", evt.val ? 1 : 0, (unsigned long)(lag * portTICK_PERIOD_MS));

        bool was = s_recording;
        s_recording = evt.val ? true : false;

        if (s_recording) {
            ui_apply_recording_pre();
        } else {
            ui_apply_recording_stop(was);
        }
    }
}

int ui_lvgl_init(void)
{
    ESP_LOGI(TAG, "ui_lvgl_init begin");
    ESP_LOGI(TAG, "[diag] lvgl ver %d.%d.%d", lv_version_major(), lv_version_minor(), lv_version_patch());
    diag_dump_heap("start");

    // 1) Init LVGL port (creates task/timers/locking).
    const lvgl_port_cfg_t lvgl_cfg = ESP_LVGL_PORT_INIT_CONFIG();
    esp_err_t err = lvgl_port_init(&lvgl_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "lvgl_port_init failed: %s", esp_err_to_name(err));
        return -1;
    }
    diag_dump_heap("after lvgl_port_init");

    // 2) Init display (SH8601 QSPI) and register it in LVGL.
    const spi_bus_config_t buscfg = SH8601_PANEL_BUS_QSPI_CONFIG(
        LCD_PIN_PCLK,
        LCD_PIN_DATA0,
        LCD_PIN_DATA1,
        LCD_PIN_DATA2,
        LCD_PIN_DATA3,
        (LCD_H_RES * 80 * sizeof(uint16_t))
    );
    ESP_LOGI(TAG, "spi_bus_initialize");
    ESP_ERROR_CHECK(spi_bus_initialize(LCD_SPI_HOST, &buscfg, SPI_DMA_CH_AUTO));

    const esp_lcd_panel_io_spi_config_t io_config = SH8601_PANEL_IO_QSPI_CONFIG(LCD_PIN_CS, NULL, NULL);
    ESP_LOGI(TAG, "esp_lcd_new_panel_io_spi");
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)(uintptr_t)LCD_SPI_HOST, &io_config, &s_io));

    sh8601_vendor_config_t vendor_config = {
        .init_cmds = lcd_init_cmds,
        .init_cmds_size = sizeof(lcd_init_cmds) / sizeof(lcd_init_cmds[0]),
        .flags = {
            .use_qspi_interface = 1,
        },
    };
    const esp_lcd_panel_dev_config_t panel_config = {
        .reset_gpio_num = LCD_PIN_RST,
        .rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB,
        .bits_per_pixel = 16,
        .vendor_config = &vendor_config,
    };
    ESP_LOGI(TAG, "esp_lcd_new_panel_sh8601");
    ESP_ERROR_CHECK(esp_lcd_new_panel_sh8601(s_io, &panel_config, &s_panel));
    ESP_LOGI(TAG, "panel_reset");
    ESP_ERROR_CHECK(esp_lcd_panel_reset(s_panel));
    ESP_LOGI(TAG, "panel_init");
    ESP_ERROR_CHECK(esp_lcd_panel_init(s_panel));
    ESP_ERROR_CHECK(esp_lcd_panel_set_gap(s_panel, LCD_X_GAP, LCD_Y_GAP));
    ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(s_panel, true));
    diag_dump_heap("after panel init");

    // Avoid -Werror=missing-braces by assigning fields explicitly.
    lvgl_port_display_cfg_t disp_cfg;
    memset(&disp_cfg, 0, sizeof(disp_cfg));
    disp_cfg.io_handle = s_io;
    disp_cfg.panel_handle = s_panel;
    disp_cfg.control_handle = NULL;
    // Must fit in DMA-capable internal RAM. Use double buffering to reduce visible stutter/tearing on partial refresh.
    disp_cfg.buffer_size = LCD_H_RES * 40; // pixels
    disp_cfg.double_buffer = true;
    disp_cfg.trans_size = 0;
    disp_cfg.hres = LCD_H_RES;
    disp_cfg.vres = LCD_V_RES;
    disp_cfg.monochrome = false;
    disp_cfg.rotation.swap_xy = false;
    disp_cfg.rotation.mirror_x = false;
    disp_cfg.rotation.mirror_y = false;
    disp_cfg.rounder_cb = NULL;
    disp_cfg.color_format = LV_COLOR_FORMAT_RGB565;
    disp_cfg.flags.buff_dma = 1;
    disp_cfg.flags.buff_spiram = 0;
    disp_cfg.flags.sw_rotate = 0;
    // Fix RGB565 byte order (otherwise red/blue swapped for images)
    disp_cfg.flags.swap_bytes = 1;
    disp_cfg.flags.full_refresh = 0;
    disp_cfg.flags.direct_mode = 0;

    s_disp = lvgl_port_add_disp(&disp_cfg);
    if (!s_disp) {
        ESP_LOGE(TAG, "lvgl_port_add_disp failed");
        return -1;
    }
    ESP_LOGI(TAG, "display registered in LVGL");

    // 3) Init touch (I2C) and register as LVGL input
    ESP_LOGI(TAG, "i2c init");
    ESP_RETURN_ON_ERROR(sonya_board_i2c_init(), TAG, "i2c init");
    i2c_master_bus_handle_t bus = sonya_board_i2c_bus();
    if (!bus) {
        ESP_LOGE(TAG, "i2c bus null");
        return -1;
    }

    const esp_lcd_touch_config_t tp_cfg = {
        .x_max = LCD_H_RES,
        .y_max = LCD_V_RES,
        .rst_gpio_num = TOUCH_PIN_RST,
        .int_gpio_num = TOUCH_PIN_INT,
        .levels = {
            .reset = 0,
            .interrupt = 0,
        },
        .flags = {
            .swap_xy = 0,
            .mirror_x = 0,
            .mirror_y = 0,
        },
    };
    esp_lcd_panel_io_handle_t tp_io = NULL;
    esp_lcd_panel_io_i2c_config_t tp_io_cfg = ESP_LCD_TOUCH_IO_I2C_FT5x06_CONFIG();
    tp_io_cfg.scl_speed_hz = 400000;
    ESP_RETURN_ON_ERROR(esp_lcd_new_panel_io_i2c(bus, &tp_io_cfg, &tp_io), TAG, "new_panel_io_i2c");
    ESP_RETURN_ON_ERROR(esp_lcd_touch_new_i2c_ft5x06(tp_io, &tp_cfg, &s_touch), TAG, "touch_new");

    const lvgl_port_touch_cfg_t touch_cfg = {
        .disp = s_disp,
        .handle = s_touch,
    };
    s_indev = lvgl_port_add_touch(&touch_cfg);
    if (!s_indev) {
        ESP_LOGE(TAG, "lvgl_port_add_touch failed");
        return -1;
    }
    ESP_LOGI(TAG, "touch registered in LVGL");

    // Simple screen: big center label + small state badge.
    lvgl_port_lock(0);
    lv_obj_t *scr = lv_display_get_screen_active(s_disp);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(scr, lv_color_black(), 0);
    lv_obj_set_style_text_color(scr, lv_color_white(), 0);

    s_label = lv_label_create(scr);
    lv_label_set_text(s_label, "SONYA");
    lv_obj_set_style_text_font(s_label, &lv_font_montserrat_28, 0);
    lv_obj_center(s_label);

    // Spinner (recording indicator), hidden by default
    s_spinner = lv_spinner_create(scr);
    lv_spinner_set_anim_params(s_spinner, 700, 270);
    lv_obj_set_size(s_spinner, 120, 120);
    lv_obj_center(s_spinner);
    lv_obj_add_flag(s_spinner, LV_OBJ_FLAG_HIDDEN);
    // Make spinner region opaque to avoid trails/tearing artifacts
    lv_obj_set_style_bg_opa(s_spinner, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_spinner, lv_color_black(), LV_PART_MAIN);
    lv_obj_set_style_arc_width(s_spinner, 12, LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(s_spinner, lv_color_make(0x6A, 0x5A, 0xFF), LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(s_spinner, lv_color_make(0x20, 0x20, 0x20), LV_PART_MAIN);

    // OK checkmark (animated), hidden by default
    s_ok = lv_label_create(scr);
    lv_label_set_text(s_ok, LV_SYMBOL_OK);
    lv_obj_set_style_text_font(s_ok, &lv_font_montserrat_28, 0);
    lv_obj_set_style_text_color(s_ok, lv_color_make(0x40, 0xE0, 0x40), 0);
    lv_obj_center(s_ok);
    lv_obj_set_style_opa(s_ok, LV_OPA_0, LV_PART_MAIN);
    lv_obj_add_flag(s_ok, LV_OBJ_FLAG_HIDDEN);

    s_state = lv_label_create(scr);
    lv_label_set_text(s_state, "ADV");
    lv_obj_set_style_text_font(s_state, &lv_font_montserrat_20, 0);
    lv_obj_align(s_state, LV_ALIGN_TOP_LEFT, UI_X_PAD + UI_X_SHIFT_10P, UI_X_PAD + UI_TOP_Y_3P);

    s_bt = lv_image_create(scr);
    lv_obj_align(s_bt, LV_ALIGN_TOP_LEFT, UI_X_PAD + UI_X_SHIFT_10P, 36 + UI_TOP_Y_3P);

    s_bat = lv_label_create(scr);
    lv_obj_set_style_text_font(s_bat, &lv_font_montserrat_20, 0);
    lv_obj_align(s_bat, LV_ALIGN_TOP_RIGHT, -(UI_X_PAD + UI_X_SHIFT_10P), UI_X_PAD + UI_TOP_Y_3P);

    refresh_state();
    refresh_top_icons();

    // DIAG: avoid decoding assets in main task (can blow the stack).
    if (!s_bt_imgs_inited) ESP_LOGW(TAG, "[diag] bt images not initialized");
    ESP_LOGI(TAG, "[diag] assets: bt_off=%u bt_on=%u",
             (unsigned)s_img_bt_off.data_size, (unsigned)s_img_bt_on.data_size);
    lvgl_port_unlock();

    // UI event queue + async worker (low priority) to avoid impacting audio capture.
    if (!s_evt_q) {
        s_evt_q = xQueueCreate(1, sizeof(ui_evt_t));
        if (!s_evt_q) {
            ESP_LOGE(TAG, "ui evt queue create failed");
            return -1;
        }
        xTaskCreate(ui_evt_task, "ui_evt", 3072, NULL, 3, &s_evt_task);
    }

    diag_dump_heap("end");
    ESP_LOGI(TAG, "ui_lvgl_init ok");
    return 0;
}

void ui_lvgl_set_connected(bool connected)
{
    s_connected = connected;
    lvgl_port_lock(0);
    refresh_state();
    refresh_top_icons();
    lvgl_port_unlock();
}

void ui_lvgl_set_recording(bool recording)
{
    s_recording = recording;
    if (!s_evt_q) {
        ESP_LOGW(TAG, "ui evt queue not ready (drop recording=%d)", recording ? 1 : 0);
        return;
    }
    ui_evt_t evt = {
        .type = UI_EVT_RECORDING,
        .val = recording ? 1 : 0,
        .tick_posted = (uint32_t)xTaskGetTickCount(),
    };
    xQueueOverwrite(s_evt_q, &evt);
}

void ui_lvgl_set_error(bool error)
{
    s_error = error;
    lvgl_port_lock(0);
    refresh_state();
    refresh_top_icons();
    lvgl_port_unlock();
}

void ui_lvgl_show_message(const char *msg, uint32_t ms)
{
    // Minimal: show text and restore "SONYA" after timeout.
    lvgl_port_lock(0);
    set_label_text(msg);
    if (s_restore_timer) {
        lv_timer_del(s_restore_timer);
        s_restore_timer = NULL;
    }
    if (ms > 0) {
        s_restore_timer = lv_timer_create(restore_timer_cb, ms, NULL);
    }
    lvgl_port_unlock();
}

void ui_lvgl_show_ok(uint32_t ms)
{
    lvgl_port_lock(0);
    if (s_spinner) lv_obj_add_flag(s_spinner, LV_OBJ_FLAG_HIDDEN);

    if (s_ok) {
        lv_obj_clear_flag(s_ok, LV_OBJ_FLAG_HIDDEN);
        lv_obj_move_foreground(s_ok);
        lv_obj_set_style_opa(s_ok, LV_OPA_0, LV_PART_MAIN);

        lv_anim_t a;
        lv_anim_init(&a);
        lv_anim_set_var(&a, s_ok);
        lv_anim_set_exec_cb(&a, ok_set_opa);
        lv_anim_set_values(&a, 0, 255);
        lv_anim_set_time(&a, 160);
        lv_anim_set_path_cb(&a, lv_anim_path_ease_out);
        lv_anim_start(&a);
    }

    if (s_label) lv_obj_add_flag(s_label, LV_OBJ_FLAG_HIDDEN);

    if (s_restore_timer) {
        lv_timer_del(s_restore_timer);
        s_restore_timer = NULL;
    }
    if (ms > 0) s_restore_timer = lv_timer_create(restore_timer_cb, ms, NULL);
    lvgl_port_unlock();
}

