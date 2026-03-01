#include "status_screen.h"

#include "sonya_ble.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"
#include "esp_check.h"

#include <stdint.h>

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
};

static inline uint16_t rgb565(uint8_t r, uint8_t g, uint8_t b)
{
    return (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

// 5x7 bitmap font: space, A-Z (subset we need). Each char 5 columns x 7 rows, column-major, LSB=top.
#define FONT_W 5
#define FONT_H 7
#define FONT_CHAR_W (FONT_W + 1)  // 1px gap
static const uint8_t font_5x7[15][5] = {
    [0]  = {0x00,0x00,0x00,0x00,0x00},  // space
    [1]  = {0x7E,0x11,0x11,0x11,0x7E},  // A
    [2]  = {0x7F,0x49,0x49,0x49,0x36},  // B
    [3]  = {0x3E,0x41,0x41,0x41,0x22},  // C
    [4]  = {0x7F,0x41,0x41,0x22,0x1C},  // D
    [5]  = {0x7F,0x49,0x49,0x49,0x41},  // E
    [6]  = {0x7F,0x08,0x14,0x22,0x41},  // K
    [7]  = {0x7F,0x40,0x40,0x40,0x40},  // L
    [8]  = {0x7F,0x02,0x0C,0x10,0x7F},  // N
    [9]  = {0x3E,0x41,0x41,0x41,0x3E},  // O
    [10] = {0x7F,0x09,0x19,0x29,0x46},  // R
    [11] = {0x70,0x08,0x07,0x08,0x70},  // V
    [12] = {0x7F,0x08,0x08,0x08,0x7F},  // H
    [13] = {0x41,0x41,0x7F,0x41,0x41},  // I
    [14] = {0x07,0x08,0x70,0x08,0x07},  // Y
};
static int font_char_index(char c) {
    if (c == ' ') return 0;
    if (c >= 'A' && c <= 'E') return 1 + (c - 'A');
    if (c == 'H') return 12;
    if (c == 'I') return 13;
    if (c == 'K') return 6;
    if (c == 'L') return 7;
    if (c >= 'N' && c <= 'O') return 8 + (c - 'N');
    if (c == 'R') return 10;
    if (c == 'V') return 11;
    if (c == 'Y') return 14;
    return 0;
}

// "Console-like" log rendering (large text, no blinking)
#define TEXT_SCALE 4
#define LINE_H ((FONT_H * TEXT_SCALE) + TEXT_SCALE)  // 28 + 4 = 32px
#define LINE_MARGIN_X 4
// Nudge content right/down a bit (user preference). Keep it proportional to the screen.
#define CONTENT_OFF_X ((LCD_H_RES / 20) + (2 * FONT_CHAR_W * TEXT_SCALE))   // ~5% + ~2 chars
#define CONTENT_OFF_Y (LCD_V_RES / 40)   // ~2.5%
#define LOG_MAX_LINES ((LCD_V_RES - CONTENT_OFF_Y) / LINE_H)
#define LOG_LINE_LEN 24

static char s_log[LOG_MAX_LINES][LOG_LINE_LEN];
static int s_log_count = 0;   // number of valid lines (<= LOG_MAX_LINES)
static int s_log_head = 0;    // index of newest line

static char s_msg[LOG_LINE_LEN];
static volatile TickType_t s_msg_until_tick = 0;

static void log_add(const char *line)
{
    if (!line) return;
    // advance ring
    s_log_head = (s_log_head + 1) % LOG_MAX_LINES;
    if (s_log_count < LOG_MAX_LINES) s_log_count++;

    // copy & uppercase (we only have A-Z and space in the font)
    int i = 0;
    for (; i < LOG_LINE_LEN - 1 && line[i]; i++) {
        char c = line[i];
        if (c >= 'a' && c <= 'z') c = (char)(c - 'a' + 'A');
        if ((c >= 'A' && c <= 'Z') || c == ' ') {
            s_log[s_log_head][i] = c;
        } else if (c == '_' || c == '-' || c == ':') {
            s_log[s_log_head][i] = ' ';
        } else {
            s_log[s_log_head][i] = ' ';
        }
    }
    s_log[s_log_head][i] = '\0';
}

static const char *log_get_line(int idx_from_oldest)
{
    // idx_from_oldest: 0..s_log_count-1
    int oldest = (s_log_head - (s_log_count - 1) + LOG_MAX_LINES) % LOG_MAX_LINES;
    int idx = (oldest + idx_from_oldest) % LOG_MAX_LINES;
    return s_log[idx];
}

static esp_err_t draw_solid(uint16_t color565);

static esp_err_t draw_line_text(int y, const char *str, uint16_t fg, uint16_t bg)
{
    if (!s_panel) return ESP_ERR_INVALID_STATE;

    // SH8601: coordinates divisible by 2
    int ys = (y >> 1) << 1;
    int ye = ((y + LINE_H) >> 1) << 1;
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

    for (size_t i = 0; i < buf_px; i++) buf[i] = bg;

    if (str) {
        int x0 = LINE_MARGIN_X + CONTENT_OFF_X;
        x0 = (x0 >> 1) << 1;
        for (int ci = 0; str[ci]; ci++) {
            int idx = font_char_index(str[ci]);
            int cx = x0 + ci * (FONT_CHAR_W * TEXT_SCALE);
            for (int col = 0; col < FONT_W; col++) {
                uint8_t bits = font_5x7[idx][col];
                for (int row = 0; row < FONT_H; row++) {
                    if (bits & (1u << row)) {
                        int px0 = cx + col * TEXT_SCALE;
                        int py0 = (row * TEXT_SCALE);
                        for (int dy = 0; dy < TEXT_SCALE; dy++) {
                            int py = py0 + dy;
                            if (py < 0 || py >= h) continue;
                            uint16_t *dst = &buf[(size_t)py * (size_t)LCD_H_RES];
                            for (int dx = 0; dx < TEXT_SCALE; dx++) {
                                int px = px0 + dx;
                                if (px < 0 || px >= LCD_H_RES) continue;
                                dst[px] = fg;
                            }
                        }
                    }
                }
            }
        }
    }

    return esp_lcd_panel_draw_bitmap(s_panel, 0, ys, LCD_H_RES, ye, buf);
}

static esp_err_t render_log_screen(uint16_t fg, uint16_t bg)
{
    // Clear full screen quickly (chunked), then draw each log line.
    ESP_RETURN_ON_ERROR(draw_solid(bg), TAG, "draw_solid");

    int visible = s_log_count;
    if (visible > LOG_MAX_LINES) visible = LOG_MAX_LINES;
    for (int i = 0; i < visible; i++) {
        int y = CONTENT_OFF_Y + i * LINE_H;
        ESP_RETURN_ON_ERROR(draw_line_text(y, log_get_line(i), fg, bg), TAG, "draw_line_text");
    }
    return ESP_OK;
}

static esp_err_t render_message_screen(const char *msg, uint16_t fg, uint16_t bg)
{
    ESP_RETURN_ON_ERROR(draw_solid(bg), TAG, "draw_solid");
    int y = (LCD_V_RES / 2) - (LINE_H / 2);
    if (y < 0) y = 0;
    return draw_line_text(y, msg, fg, bg);
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

    for (size_t i = 0; i < buf_px; i++) buf[i] = color565;

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

static void __attribute__((unused)) task_screen(void *arg)
{
    (void)arg;

    bool last_conn = false;
    bool last_rec = false;
    bool last_err = false;
    bool last_msg_active = false;
    bool first = true;

    for (;;) {
        bool conn = sonya_ble_is_connected();
        bool rec = s_recording;
        bool err = s_error;
        TickType_t now = xTaskGetTickCount();
        bool msg_active = (s_msg_until_tick != 0) && (now < s_msg_until_tick);
        bool changed = first;

        if (first) {
            log_add("BLE ADV");
            changed = true;
            first = false;
        }

        if (conn != last_conn) {
            log_add(conn ? "BLE CONN" : "BLE ADV");
            changed = true;
            last_conn = conn;
        }
        if (rec != last_rec) {
            log_add(rec ? "REC ON" : "REC END");
            changed = true;
            last_rec = rec;
        }
        if (err != last_err) {
            log_add(err ? "ERR" : "ERR OK");
            changed = true;
            last_err = err;
        }

        if (msg_active != last_msg_active) {
            changed = true;
            last_msg_active = msg_active;
        }

        if (changed) {
            esp_err_t e = msg_active
                ? render_message_screen(s_msg, rgb565(255, 255, 255), rgb565(0, 0, 0))
                : render_log_screen(rgb565(255, 255, 255), rgb565(0, 0, 0));
            if (e != ESP_OK) ESP_LOGW(TAG, "render failed: %s", esp_err_to_name(e));
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

void status_screen_show_message(const char *msg, uint32_t ms)
{
    if (!msg || ms == 0) {
        s_msg_until_tick = 0;
        return;
    }
    // Copy & uppercase (we only have A-Z and space in the font).
    int i = 0;
    for (; i < LOG_LINE_LEN - 1 && msg[i]; i++) {
        char c = msg[i];
        if (c >= 'a' && c <= 'z') c = (char)(c - 'a' + 'A');
        if ((c >= 'A' && c <= 'Z') || c == ' ') {
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
    ESP_ERROR_CHECK(esp_lcd_panel_io_tx_param(s_io, 0x29, NULL, 0)); // Display ON
    const uint8_t br = 0xFF;
    ESP_ERROR_CHECK(esp_lcd_panel_io_tx_param(s_io, 0x51, &br, 1));
    {
        uint8_t pm = 0;
        esp_err_t e = esp_lcd_panel_io_rx_param(s_io, 0x0A, &pm, 1);
        ESP_LOGI(TAG, "lcd rx 0x0A power_mode: %s %02X", esp_err_to_name(e), pm);
        uint8_t dm = 0;
        e = esp_lcd_panel_io_rx_param(s_io, 0x0D, &dm, 1);
        ESP_LOGI(TAG, "lcd rx 0x0D display_mode: %s %02X", esp_err_to_name(e), dm);
    }

    // Hardware blink test: 6 frames alternating red/blue to confirm panel responds.
    // Remove once display is confirmed working.
    ESP_LOGI(TAG, "hw blink test start");
    for (int i = 0; i < 6; i++) {
        uint16_t color = (i % 2 == 0) ? rgb565(255, 0, 0) : rgb565(0, 0, 255);
        ESP_ERROR_CHECK(draw_solid(color));
        vTaskDelay(pdMS_TO_TICKS(400));
    }
    ESP_ERROR_CHECK(draw_solid(rgb565(0, 0, 0)));
    ESP_LOGI(TAG, "hw blink test done");

    xTaskCreate(task_screen, "status_screen", 4096, NULL, 5, NULL);
#else
    ESP_LOGI(TAG, "disabled by CONFIG_STATUS_SCREEN_ENABLE");
#endif
}

