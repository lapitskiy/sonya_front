#include "status_screen.h"

#include "sonya_ble.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"
#include "esp_check.h"

#include <stdio.h>
#include <stdint.h>
#include <time.h>

#include "driver/spi_master.h"
#include "esp_heap_caps.h"

#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_io.h"

#include "esp_lcd_sh8601.h"

static const char *TAG = "status_screen";

// Waveshare ESP32-S3 Touch AMOLED 2.06 (QSPI) pinout
// (matches common BSP pinout used for this board family)
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
// SH8601 on this AMOLED module has a visible-area offset (columns start at 0x16).
// Compensate via esp_lcd_panel_set_gap() so x=0 maps to the left visible pixel.
#define LCD_X_GAP         0x16
#define LCD_Y_GAP         0

static esp_lcd_panel_handle_t s_panel = NULL;
static esp_lcd_panel_io_handle_t __attribute__((unused)) s_io = NULL;

static volatile bool s_recording = false;
static volatile bool s_error = false;
static volatile bool s_app_ready = false;
static volatile bool s_time_synced = false;
static volatile int16_t s_tz_offset_min = 0;

// Custom init sequence known to work on Waveshare AMOLED boards with SH8601 controller.
static const sh8601_lcd_init_cmd_t __attribute__((unused)) lcd_init_cmds[] = {
    {0x11, (uint8_t[]){0x00}, 0, 120},                 // Sleep out
    {0xC4, (uint8_t[]){0x80}, 1, 0},
    {0x44, (uint8_t[]){0x01, 0xD1}, 2, 0},
    {0x35, (uint8_t[]){0x00}, 1, 0},                   // TE on (param)
    {0x53, (uint8_t[]){0x20}, 1, 10},
    {0x63, (uint8_t[]){0xFF}, 1, 10},
    // Start with brightness 0. We'll only enable visibility after clearing the frame buffer,
    // otherwise the panel can briefly show garbage/white during boot.
    {0x51, (uint8_t[]){0x00}, 1, 10},
    {0x2A, (uint8_t[]){0x00, 0x16, 0x01, 0xAF}, 4, 0},  // Column address set (0x16..0x1AF) -> aligns 410px width with panel
    {0x2B, (uint8_t[]){0x00, 0x00, 0x01, 0xF5}, 4, 0},  // Row address set (0..0x1F5) -> 502px height
    {0x29, (uint8_t[]){0x00}, 0, 10},                   // Display ON
    {0x51, (uint8_t[]){0xFF}, 1, 0},                    // Brightness MAX
};

static inline uint16_t rgb565(uint8_t r, uint8_t g, uint8_t b)
{
    return (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

static inline uint16_t panel_rgb565(uint16_t color)
{
    return (uint16_t)((color << 8) | (color >> 8));
}

// 5x7 bitmap font: space, A-Z, 0-9, colon, dash, dot. Column-major, LSB=top.
#define FONT_W 5
#define FONT_H 7
#define FONT_CHAR_W (FONT_W + 1)  // 1px gap
static const uint8_t font_5x7[][5] = {
    [0]  = {0x00,0x00,0x00,0x00,0x00},  // space
    [1]  = {0x7E,0x11,0x11,0x11,0x7E},  // A
    [2]  = {0x7F,0x49,0x49,0x49,0x36},  // B
    [3]  = {0x3E,0x41,0x41,0x41,0x22},  // C
    [4]  = {0x7F,0x41,0x41,0x22,0x1C},  // D
    [5]  = {0x7F,0x49,0x49,0x49,0x41},  // E
    [6]  = {0x7F,0x09,0x09,0x09,0x01},  // F
    [7]  = {0x3E,0x41,0x49,0x49,0x7A},  // G
    [8]  = {0x7F,0x08,0x08,0x08,0x7F},  // H
    [9]  = {0x41,0x41,0x7F,0x41,0x41},  // I
    [10] = {0x20,0x40,0x41,0x3F,0x01},  // J
    [11] = {0x7F,0x08,0x14,0x22,0x41},  // K
    [12] = {0x7F,0x40,0x40,0x40,0x40},  // L
    [13] = {0x7F,0x02,0x0C,0x02,0x7F},  // M
    [14] = {0x7F,0x04,0x08,0x10,0x7F},  // N
    [15] = {0x3E,0x41,0x41,0x41,0x3E},  // O
    [16] = {0x7F,0x09,0x09,0x09,0x06},  // P
    [17] = {0x3E,0x41,0x51,0x21,0x5E},  // Q
    [18] = {0x7F,0x09,0x19,0x29,0x46},  // R
    [19] = {0x46,0x49,0x49,0x49,0x31},  // S
    [20] = {0x01,0x01,0x7F,0x01,0x01},  // T
    [21] = {0x3F,0x40,0x40,0x40,0x3F},  // U
    [22] = {0x1F,0x20,0x40,0x20,0x1F},  // V
    [23] = {0x7F,0x20,0x18,0x20,0x7F},  // W
    [24] = {0x63,0x14,0x08,0x14,0x63},  // X
    [25] = {0x07,0x08,0x70,0x08,0x07},  // Y
    [26] = {0x61,0x51,0x49,0x45,0x43},  // Z
    [27] = {0x3E,0x51,0x49,0x45,0x3E},  // 0
    [28] = {0x00,0x42,0x7F,0x40,0x00},  // 1
    [29] = {0x42,0x61,0x51,0x49,0x46},  // 2
    [30] = {0x21,0x41,0x45,0x4B,0x31},  // 3
    [31] = {0x18,0x14,0x12,0x7F,0x10},  // 4
    [32] = {0x27,0x45,0x45,0x45,0x39},  // 5
    [33] = {0x3C,0x4A,0x49,0x49,0x30},  // 6
    [34] = {0x01,0x71,0x09,0x05,0x03},  // 7
    [35] = {0x36,0x49,0x49,0x49,0x36},  // 8
    [36] = {0x06,0x49,0x49,0x29,0x1E},  // 9
    [37] = {0x00,0x36,0x36,0x00,0x00},  // :
    [38] = {0x08,0x08,0x08,0x08,0x08},  // -
    [39] = {0x00,0x60,0x60,0x00,0x00},  // .
};
static int font_char_index(char c) {
    if (c == ' ') return 0;
    if (c >= 'A' && c <= 'Z') return 1 + (c - 'A');
    if (c >= '0' && c <= '9') return 27 + (c - '0');
    if (c == ':') return 37;
    if (c == '-') return 38;
    if (c == '.') return 39;
    return 0;
}

// "Console-like" log rendering (large text, no blinking)
#define TEXT_SCALE 4
#define LINE_H ((FONT_H * TEXT_SCALE) + TEXT_SCALE)  // 28 + 4 = 32px
#define LINE_MARGIN_X 4
// Nudge content right/down a bit (user preference). Keep it proportional to the screen.
#define CONTENT_OFF_X ((LCD_H_RES / 20) + (2 * FONT_CHAR_W * TEXT_SCALE))   // ~5% + ~2 chars
#define CONTENT_OFF_Y (LCD_V_RES / 40)   // ~2.5%
#define LOG_LINE_LEN 24

static char s_msg[LOG_LINE_LEN];
static volatile TickType_t s_msg_until_tick = 0;

static esp_err_t draw_solid(uint16_t color565);
static esp_err_t draw_rect(int x, int y, int w, int h, uint16_t color565);

static int text_px_width(const char *str, int scale)
{
    int n = 0;
    if (!str) return 0;
    while (str[n]) n++;
    if (n <= 0) return 0;
    return (((n * FONT_CHAR_W) - 1) * scale);
}

static esp_err_t draw_text_at(int x0, int y, const char *str, int scale, uint16_t fg, uint16_t bg)
{
    if (!s_panel) return ESP_ERR_INVALID_STATE;
    if (scale <= 0) return ESP_ERR_INVALID_ARG;

    // SH8601: coordinates divisible by 2
    int ys = (y >> 1) << 1;
    int line_h = (FONT_H * scale) + scale;
    int ye = ((y + line_h) >> 1) << 1;
    if (ye <= ys) ye = ys + 2;

    static uint16_t *buf = NULL;
    static size_t buf_px = 0;
    const int h = ye - ys;
    const size_t want_px = (size_t)LCD_H_RES * (size_t)h;
    if (!buf || buf_px != want_px) {
        if (buf) heap_caps_free(buf);
        buf = (uint16_t *)heap_caps_malloc(want_px * sizeof(uint16_t), MALLOC_CAP_DMA);
        if (!buf) return ESP_ERR_NO_MEM;
        buf_px = want_px;
    }

    const uint16_t bg_panel = panel_rgb565(bg);
    const uint16_t fg_panel = panel_rgb565(fg);
    for (size_t i = 0; i < buf_px; i++) buf[i] = bg_panel;

    if (str) {
        x0 = (x0 >> 1) << 1;
        for (int ci = 0; str[ci]; ci++) {
            int idx = font_char_index(str[ci]);
            int cx = x0 + ci * (FONT_CHAR_W * scale);
            for (int col = 0; col < FONT_W; col++) {
                uint8_t bits = font_5x7[idx][col];
                for (int row = 0; row < FONT_H; row++) {
                    if (bits & (1u << row)) {
                        int px0 = cx + col * scale;
                        int py0 = (row * scale);
                        for (int dy = 0; dy < scale; dy++) {
                            int py = py0 + dy;
                            if (py < 0 || py >= h) continue;
                            uint16_t *dst = &buf[(size_t)py * (size_t)LCD_H_RES];
                            for (int dx = 0; dx < scale; dx++) {
                                int px = px0 + dx;
                                if (px < 0 || px >= LCD_H_RES) continue;
                                dst[px] = fg_panel;
                            }
                        }
                    }
                }
            }
        }
    }

    return esp_lcd_panel_draw_bitmap(s_panel, 0, ys, LCD_H_RES, ye, buf);
}

static esp_err_t draw_line_text(int y, const char *str, uint16_t fg, uint16_t bg)
{
    return draw_text_at(LINE_MARGIN_X + CONTENT_OFF_X, y, str, TEXT_SCALE, fg, bg);
}

static esp_err_t draw_center_text(int y, const char *str, int scale, uint16_t fg, uint16_t bg)
{
    int x = (LCD_H_RES - text_px_width(str, scale)) / 2;
    if (x < 0) x = 0;
    return draw_text_at(x, y, str, scale, fg, bg);
}

static esp_err_t draw_time_block(uint16_t fg, uint16_t bg)
{
    if (!s_time_synced) return ESP_OK;

    time_t now = time(NULL);
    now += (time_t)s_tz_offset_min * 60;

    struct tm tm_now;
    if (gmtime_r(&now, &tm_now) == NULL) return ESP_OK;

    char hhmm[6];
    char date[40];
    snprintf(hhmm, sizeof(hhmm), "%02d:%02d", tm_now.tm_hour, tm_now.tm_min);
    snprintf(date, sizeof(date), "%02d.%02d.%04d",
             tm_now.tm_mday, tm_now.tm_mon + 1, tm_now.tm_year + 1900);

    ESP_RETURN_ON_ERROR(draw_center_text(72, hhmm, 8, fg, bg), TAG, "time text");
    return draw_center_text(152, date, 4, fg, bg);
}

static esp_err_t render_message_screen(const char *msg, uint16_t fg, uint16_t bg)
{
    ESP_RETURN_ON_ERROR(draw_solid(bg), TAG, "draw_solid");
    ESP_RETURN_ON_ERROR(draw_time_block(fg, bg), TAG, "time block");
    int y = (LCD_V_RES / 2) - (LINE_H / 2);
    if (y < 0) y = 0;
    return draw_line_text(y, msg, fg, bg);
}

static esp_err_t draw_checkmark(uint16_t color)
{
    const int box = 18;
    for (int i = 0; i < 8; i++) {
        ESP_RETURN_ON_ERROR(draw_rect(110 + i * 8, 250 + i * 8, box, box, color), TAG, "check left");
    }
    for (int i = 0; i < 16; i++) {
        ESP_RETURN_ON_ERROR(draw_rect(170 + i * 8, 305 - i * 8, box, box, color), TAG, "check right");
    }
    return ESP_OK;
}

static esp_err_t render_ready_screen(void)
{
    const uint16_t bg = rgb565(82, 112, 88);
    const uint16_t fg = rgb565(246, 238, 220);
    ESP_RETURN_ON_ERROR(draw_solid(bg), TAG, "ready bg");
    ESP_RETURN_ON_ERROR(draw_time_block(fg, bg), TAG, "ready time");
    ESP_RETURN_ON_ERROR(draw_checkmark(fg), TAG, "ready check");
    ESP_RETURN_ON_ERROR(draw_center_text(370, "PRESS BTN", 5, fg, bg), TAG, "ready text");
    return ESP_OK;
}

static esp_err_t render_boot_splash(void)
{
    const uint16_t bg = rgb565(46, 43, 38);
    const uint16_t fg = rgb565(246, 238, 220);
    const uint16_t bar_bg = rgb565(92, 84, 73);
    const uint16_t bar_fg = rgb565(170, 142, 112);
    const int bar_x = 80;
    const int bar_y = 330;
    const int bar_w = LCD_H_RES - (bar_x * 2);
    const int bar_h = 18;

    for (int i = 0; i < 6; i++) {
        ESP_RETURN_ON_ERROR(draw_solid(bg), TAG, "boot bg");
        ESP_RETURN_ON_ERROR(draw_center_text(210, "SONYA", 6, fg, bg), TAG, "boot title");
        ESP_RETURN_ON_ERROR(draw_rect(bar_x, bar_y, bar_w, bar_h, bar_bg), TAG, "boot bar bg");
        ESP_RETURN_ON_ERROR(draw_rect(bar_x, bar_y, (bar_w * (i + 1)) / 6, bar_h, bar_fg), TAG, "boot bar fg");
        vTaskDelay(pdMS_TO_TICKS(250));
    }
    return ESP_OK;
}

static void status_pick_main(bool conn, bool app_ready, bool rec, bool err,
                             const char **out_msg, uint16_t *out_fg, uint16_t *out_bg)
{
    if (err) {
        *out_msg = "ERR";
        *out_fg = rgb565(246, 238, 220);
        *out_bg = rgb565(132, 78, 73);
        return;
    }
    if (rec) {
        *out_msg = "REC ON";
        *out_fg = rgb565(246, 238, 220);
        *out_bg = rgb565(142, 103, 72);
        return;
    }
    if (app_ready) {
        *out_msg = "READY";
        *out_fg = rgb565(246, 238, 220);
        *out_bg = rgb565(82, 112, 88);
        return;
    }
    if (conn) {
        *out_msg = "BLE LINK";
        *out_fg = rgb565(246, 238, 220);
        *out_bg = rgb565(96, 105, 96);
        return;
    }
    *out_msg = "BLE ADV";
    *out_fg = rgb565(246, 238, 220);
    *out_bg = rgb565(93, 86, 75);
}

static esp_err_t render_main_status(bool conn, bool app_ready, bool rec, bool err)
{
    if (app_ready && !rec && !err) {
        return render_ready_screen();
    }

    const char *msg = NULL;
    uint16_t fg = 0;
    uint16_t bg = 0;
    status_pick_main(conn, app_ready, rec, err, &msg, &fg, &bg);
    return render_message_screen(msg, fg, bg);
}

static esp_err_t draw_solid(uint16_t color565)
{
    if (!s_panel) return ESP_ERR_INVALID_STATE;

    const int chunk_lines = 8;
    static uint16_t *buf = NULL;
    static size_t buf_px = 0;

    const size_t want_px = (size_t)LCD_H_RES * (size_t)chunk_lines;
    if (!buf || buf_px != want_px) {
        // Allocate once; small buffer (410*8*2 ~ 6.6 KB)
        buf = (uint16_t *)heap_caps_malloc(want_px * sizeof(uint16_t), MALLOC_CAP_DMA);
        if (!buf) return ESP_ERR_NO_MEM;
        buf_px = want_px;
    }

    const uint16_t color_panel = panel_rgb565(color565);
    for (size_t i = 0; i < buf_px; i++) buf[i] = color_panel;

    for (int y = 0; y < LCD_V_RES; y += chunk_lines) {
        int y_end = y + chunk_lines;
        if (y_end > LCD_V_RES) y_end = LCD_V_RES;
        // SH8601 requirement: coordinates divisible by 2
        int ys = (y >> 1) << 1;
        int ye = (y_end >> 1) << 1;
        if (ye <= ys) ye = ys + 2;
        ESP_RETURN_ON_ERROR(esp_lcd_panel_draw_bitmap(s_panel, 0, ys, LCD_H_RES, ye, buf), TAG, "draw_bitmap");
    }

    return ESP_OK;
}

static esp_err_t draw_rect(int x, int y, int w, int h, uint16_t color565)
{
    if (!s_panel) return ESP_ERR_INVALID_STATE;
    if (w <= 0 || h <= 0) return ESP_OK;

    int xs = (x >> 1) << 1;
    int ys = (y >> 1) << 1;
    int xe = ((x + w) >> 1) << 1;
    int ye = ((y + h) >> 1) << 1;
    if (xs < 0) xs = 0;
    if (ys < 0) ys = 0;
    if (xe > LCD_H_RES) xe = LCD_H_RES;
    if (ye > LCD_V_RES) ye = LCD_V_RES;
    if (xe <= xs || ye <= ys) return ESP_OK;

    const size_t want_px = (size_t)(xe - xs) * (size_t)(ye - ys);
    uint16_t *buf = (uint16_t *)heap_caps_malloc(want_px * sizeof(uint16_t), MALLOC_CAP_DMA);
    if (!buf) return ESP_ERR_NO_MEM;
    const uint16_t color_panel = panel_rgb565(color565);
    for (size_t i = 0; i < want_px; i++) buf[i] = color_panel;
    esp_err_t err = esp_lcd_panel_draw_bitmap(s_panel, xs, ys, xe, ye, buf);
    heap_caps_free(buf);
    return err;
}

static void task_screen(void *arg)
{
    (void)arg;

    bool last_conn = false;
    bool last_app = false;
    bool last_rec = false;
    bool last_err = false;
    bool last_msg_active = false;
    bool last_time_synced = false;
    long last_time_minute = -1;
    bool first = true;

    for (;;) {
        bool conn = sonya_ble_is_connected();
        bool app_ready = s_app_ready;
        bool rec = s_recording;
        bool err = s_error;
        bool time_synced = s_time_synced;
        long time_minute = -1;
        if (time_synced) {
            time_t wall = time(NULL) + ((time_t)s_tz_offset_min * 60);
            time_minute = (long)(wall / 60);
        }
        TickType_t now = xTaskGetTickCount();
        bool msg_active = (s_msg_until_tick != 0) && (now < s_msg_until_tick);

        if (!conn && app_ready) {
            s_app_ready = false;
            app_ready = false;
        }

        bool changed = first || (conn != last_conn) || (app_ready != last_app) ||
                       (rec != last_rec) || (err != last_err) ||
                       (msg_active != last_msg_active) ||
                       (time_synced != last_time_synced) ||
                       (time_minute != last_time_minute);

        if (changed) {
            first = false;
            esp_err_t e;
            if (msg_active) {
                e = render_message_screen(s_msg, rgb565(246, 238, 220), rgb565(46, 43, 38));
            } else {
                e = render_main_status(conn, app_ready, rec, err);
            }
            if (e != ESP_OK) ESP_LOGW(TAG, "render failed: %s", esp_err_to_name(e));

            last_conn = conn;
            last_app = app_ready;
            last_rec = rec;
            last_err = err;
            last_msg_active = msg_active;
            last_time_synced = time_synced;
            last_time_minute = time_minute;
        }

        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void status_screen_set_recording(bool recording)
{
    s_recording = recording;
}

void status_screen_set_error(bool error)
{
    s_error = error;
}

void status_screen_set_app_ready(bool ready)
{
    s_app_ready = ready;
    if (s_panel) {
        esp_err_t e = render_main_status(sonya_ble_is_connected(), ready, s_recording, s_error);
        if (e != ESP_OK) ESP_LOGW(TAG, "render app_ready failed: %s", esp_err_to_name(e));
    }
}

void status_screen_set_time(time_t epoch, int16_t tz_offset_min)
{
    (void)epoch;
    s_tz_offset_min = tz_offset_min;
    s_time_synced = true;
    if (s_panel) {
        esp_err_t e = render_main_status(sonya_ble_is_connected(), s_app_ready, s_recording, s_error);
        if (e != ESP_OK) ESP_LOGW(TAG, "render time failed: %s", esp_err_to_name(e));
    }
}

void status_screen_show_message(const char *msg, uint32_t ms)
{
    if (!msg || ms == 0) {
        s_msg_until_tick = 0;
        return;
    }
    // Copy & uppercase to match the tiny built-in font.
    int i = 0;
    for (; i < LOG_LINE_LEN - 1 && msg[i]; i++) {
        char c = msg[i];
        if (c >= 'a' && c <= 'z') c = (char)(c - 'a' + 'A');
        if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            c == ' ' || c == ':' || c == '-' || c == '.') {
            s_msg[i] = c;
        } else {
            s_msg[i] = ' ';
        }
    }
    s_msg[i] = '\0';
    s_msg_until_tick = xTaskGetTickCount() + pdMS_TO_TICKS(ms);
}

void status_screen_init(void)
{
#if CONFIG_STATUS_SCREEN_ENABLE
    ESP_LOGI(TAG, "init");

    ESP_LOGI(TAG, "init QSPI bus");
    const spi_bus_config_t buscfg = SH8601_PANEL_BUS_QSPI_CONFIG(
        LCD_PIN_PCLK,
        LCD_PIN_DATA0,
        LCD_PIN_DATA1,
        LCD_PIN_DATA2,
        LCD_PIN_DATA3,
        (LCD_H_RES * 80 * sizeof(uint16_t))
    );
    ESP_ERROR_CHECK(spi_bus_initialize(LCD_SPI_HOST, &buscfg, SPI_DMA_CH_AUTO));

    ESP_LOGI(TAG, "install panel IO");
    esp_lcd_panel_io_spi_config_t io_config = SH8601_PANEL_IO_QSPI_CONFIG(LCD_PIN_CS, NULL, NULL);
    ESP_LOGI(TAG, "panel io: pclk=%d spi_mode=%d cmd_bits=%d param_bits=%d",
             (int)io_config.pclk_hz, (int)io_config.spi_mode, (int)io_config.lcd_cmd_bits, (int)io_config.lcd_param_bits);
    // esp_lcd_new_panel_io_spi expects an esp_lcd_spi_bus_handle_t (opaque handle), but for
    // SPI panels in IDF 5.1 this is the SPI host ID cast to a pointer-sized handle.
    // Use uintptr_t to avoid truncation/invalid pointer values.
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)(uintptr_t)LCD_SPI_HOST, &io_config, &s_io));
    {
        uint8_t id[3] = {0};
        esp_err_t e = esp_lcd_panel_io_rx_param(s_io, 0x04, id, sizeof(id));
        ESP_LOGI(TAG, "lcd rx 0x04 id: %s %02X %02X %02X", esp_err_to_name(e), id[0], id[1], id[2]);
        uint8_t st[4] = {0};
        e = esp_lcd_panel_io_rx_param(s_io, 0x09, st, sizeof(st));
        ESP_LOGI(TAG, "lcd rx 0x09 st: %s %02X %02X %02X %02X", esp_err_to_name(e), st[0], st[1], st[2], st[3]);
    }

    ESP_LOGI(TAG, "install SH8601 panel");
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
    ESP_ERROR_CHECK(esp_lcd_new_panel_sh8601(s_io, &panel_config, &s_panel));

    ESP_ERROR_CHECK(esp_lcd_panel_reset(s_panel));
    ESP_ERROR_CHECK(esp_lcd_panel_init(s_panel));
    ESP_ERROR_CHECK(esp_lcd_panel_set_gap(s_panel, LCD_X_GAP, LCD_Y_GAP));

    ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(s_panel, false));
    ESP_ERROR_CHECK(draw_solid(rgb565(0, 0, 0)));
    ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(s_panel, true));
    {
        uint8_t pm = 0;
        esp_err_t e = esp_lcd_panel_io_rx_param(s_io, 0x0A, &pm, 1);
        ESP_LOGI(TAG, "lcd rx 0x0A power_mode: %s %02X", esp_err_to_name(e), pm);
        uint8_t dm = 0;
        e = esp_lcd_panel_io_rx_param(s_io, 0x0D, &dm, 1);
        ESP_LOGI(TAG, "lcd rx 0x0D display_mode: %s %02X", esp_err_to_name(e), dm);
    }

    // Boot splash confirms the watch is starting before BLE/app state is known.
    ESP_ERROR_CHECK(render_boot_splash());

    // Show status immediately after splash.
    ESP_ERROR_CHECK(render_main_status(false, false, false, false));

    xTaskCreate(task_screen, "status_screen", 4096, NULL, 5, NULL);
#else
    ESP_LOGI(TAG, "disabled by CONFIG_STATUS_SCREEN_ENABLE");
#endif
}

