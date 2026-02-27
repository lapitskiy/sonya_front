#include "ui_lvgl_gif.h"

#include <string.h>

#include "esp_log.h"
#include "esp_heap_caps.h"

#include "draw/lv_draw_buf_private.h"

static const char *TAG = "ui_lvgl_gif";

// Embedded GIF (recording animation)
extern const uint8_t voice_recording_gif_start[] asm("_binary_voice_recording_gif_start");
extern const uint8_t voice_recording_gif_end[]   asm("_binary_voice_recording_gif_end");
extern const uint8_t done_gif_start[]            asm("_binary_done_gif_start");
extern const uint8_t done_gif_end[]              asm("_binary_done_gif_end");

static lv_obj_t *s_gif_rec = NULL;
static lv_obj_t *s_gif_done = NULL;

static bool s_gif_assets_inited = false;
static lv_image_dsc_t s_gif_rec_dsc;
static lv_image_dsc_t s_gif_done_dsc;

static void diag_dump_heap(const char *where)
{
    uint32_t free_int = (uint32_t)heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
    uint32_t free_8b  = (uint32_t)heap_caps_get_free_size(MALLOC_CAP_8BIT);
    uint32_t free_dma = (uint32_t)heap_caps_get_free_size(MALLOC_CAP_DMA);
    ESP_LOGI(TAG, "[diag] heap %s: internal=%u, dma=%u, 8bit=%u",
             where ? where : "?", (unsigned)free_int, (unsigned)free_dma, (unsigned)free_8b);
}

static void diag_dump_largest_blocks(const char *where)
{
    size_t li = heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    size_t lp = heap_caps_get_largest_free_block(MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    ESP_LOGI(TAG, "[diag] largest %s: internal=%u psram=%u",
             where ? where : "?", (unsigned)li, (unsigned)lp);
}

static bool gif_peek_size(const uint8_t *start, const uint8_t *end, uint16_t *w, uint16_t *h)
{
    if(!start || !end) return false;
    size_t size = (size_t)(end - start);
    if(size < 10) return false;
    if(memcmp(start, "GIF", 3) != 0) return false;
    if(w) *w = (uint16_t)((uint16_t)start[6] | ((uint16_t)start[7] << 8));
    if(h) *h = (uint16_t)((uint16_t)start[8] | ((uint16_t)start[9] << 8));
    return true;
}

static void diag_log_gif_asset(const char *name, const uint8_t *start, const uint8_t *end)
{
    if(!name) name = "?";
    uint32_t size = (start && end) ? (uint32_t)(end - start) : 0;
    uint16_t w = 0, h = 0;
    bool ok = gif_peek_size(start, end, &w, &h);
    uint32_t fb565 = (ok && w && h) ? (uint32_t)w * (uint32_t)h * 2u : 0u;
    ESP_LOGI(TAG, "[diag] %s: bytes=%u gif_ok=%d w=%u h=%u fb_rgb565=%u",
             name, (unsigned)size, (int)ok, (unsigned)w, (unsigned)h, (unsigned)fb565);
}

static void gif_assets_init_once(void)
{
    if(s_gif_assets_inited) return;
    memset(&s_gif_rec_dsc, 0, sizeof(s_gif_rec_dsc));
    memset(&s_gif_done_dsc, 0, sizeof(s_gif_done_dsc));

    s_gif_rec_dsc.header.magic = LV_IMAGE_HEADER_MAGIC;
    s_gif_rec_dsc.header.cf = LV_COLOR_FORMAT_RAW; // raw GIF file data
    s_gif_rec_dsc.header.w = 0;
    s_gif_rec_dsc.header.h = 0;
    s_gif_rec_dsc.header.stride = 0;
    s_gif_rec_dsc.data = voice_recording_gif_start;
    s_gif_rec_dsc.data_size = (uint32_t)(voice_recording_gif_end - voice_recording_gif_start);

    s_gif_done_dsc.header.magic = LV_IMAGE_HEADER_MAGIC;
    s_gif_done_dsc.header.cf = LV_COLOR_FORMAT_RAW; // raw GIF file data
    s_gif_done_dsc.header.w = 0;
    s_gif_done_dsc.header.h = 0;
    s_gif_done_dsc.header.stride = 0;
    s_gif_done_dsc.data = done_gif_start;
    s_gif_done_dsc.data_size = (uint32_t)(done_gif_end - done_gif_start);

    diag_log_gif_asset("voice_recording.gif", voice_recording_gif_start, voice_recording_gif_end);
    diag_log_gif_asset("done.gif", done_gif_start, done_gif_end);
    s_gif_assets_inited = true;
}

bool ui_lvgl_gif_create(lv_obj_t *parent)
{
    if(!parent) return false;
    gif_assets_init_once();

    s_gif_rec = lv_gif_create(parent);
    lv_gif_set_color_format(s_gif_rec, LV_COLOR_FORMAT_RGB565);
    lv_obj_center(s_gif_rec);
    lv_obj_add_flag(s_gif_rec, LV_OBJ_FLAG_HIDDEN);

    s_gif_done = lv_gif_create(parent);
    lv_gif_set_color_format(s_gif_done, LV_COLOR_FORMAT_RGB565);
    lv_obj_center(s_gif_done);
    lv_obj_add_flag(s_gif_done, LV_OBJ_FLAG_HIDDEN);

    return true;
}

void ui_lvgl_gif_hide_rec(void)
{
    if(s_gif_rec) lv_obj_add_flag(s_gif_rec, LV_OBJ_FLAG_HIDDEN);
}

void ui_lvgl_gif_hide_done(void)
{
    if(s_gif_done) lv_obj_add_flag(s_gif_done, LV_OBJ_FLAG_HIDDEN);
}

void ui_lvgl_gif_hide_all(void)
{
    ui_lvgl_gif_hide_rec();
    ui_lvgl_gif_hide_done();
}

bool ui_lvgl_gif_ensure_loaded_rec(void)
{
    if(!s_gif_rec) return false;
    if(lv_gif_is_loaded(s_gif_rec)) return true;

    {
        lv_draw_buf_handlers_t *h = lv_draw_buf_get_handlers();
        ESP_LOGI(TAG, "[diag] draw_buf cb: malloc=%p free=%p", h ? (void *)h->buf_malloc_cb : NULL,
                 h ? (void *)h->buf_free_cb : NULL);
    }
    diag_dump_heap("gif_rec before");
    diag_dump_largest_blocks("gif_rec before");
    lv_gif_set_src(s_gif_rec, &s_gif_rec_dsc);
    bool ok = lv_gif_is_loaded(s_gif_rec);
    ESP_LOGI(TAG, "[diag] gif_rec load: ok=%d obj=%dx%d",
             (int)ok, (int)lv_obj_get_width(s_gif_rec), (int)lv_obj_get_height(s_gif_rec));
    if(!ok) {
        uint16_t w = 0, h = 0;
        bool g = gif_peek_size(voice_recording_gif_start, voice_recording_gif_end, &w, &h);
        uint32_t need = (g && w && h) ? (uint32_t)w * (uint32_t)h * 2u : 0u;
        ESP_LOGE(TAG, "gif_rec load failed: gif_ok=%d w=%u h=%u need_rgb565=%u",
                 (int)g, (unsigned)w, (unsigned)h, (unsigned)need);
    }
    return ok;
}

bool ui_lvgl_gif_ensure_loaded_done(void)
{
    if(!s_gif_done) return false;
    if(lv_gif_is_loaded(s_gif_done)) return true;

    {
        lv_draw_buf_handlers_t *h = lv_draw_buf_get_handlers();
        ESP_LOGI(TAG, "[diag] draw_buf cb: malloc=%p free=%p", h ? (void *)h->buf_malloc_cb : NULL,
                 h ? (void *)h->buf_free_cb : NULL);
    }
    diag_dump_heap("gif_done before");
    diag_dump_largest_blocks("gif_done before");
    lv_gif_set_src(s_gif_done, &s_gif_done_dsc);
    bool ok = lv_gif_is_loaded(s_gif_done);
    ESP_LOGI(TAG, "[diag] gif_done load: ok=%d obj=%dx%d",
             (int)ok, (int)lv_obj_get_width(s_gif_done), (int)lv_obj_get_height(s_gif_done));
    if(!ok) {
        uint16_t w = 0, h = 0;
        bool g = gif_peek_size(done_gif_start, done_gif_end, &w, &h);
        uint32_t need = (g && w && h) ? (uint32_t)w * (uint32_t)h * 2u : 0u;
        ESP_LOGE(TAG, "gif_done load failed: gif_ok=%d w=%u h=%u need_rgb565=%u",
                 (int)g, (unsigned)w, (unsigned)h, (unsigned)need);
    }
    return ok;
}

void ui_lvgl_gif_show_rec(void)
{
    if(!s_gif_rec) return;
    lv_obj_clear_flag(s_gif_rec, LV_OBJ_FLAG_HIDDEN);
    lv_obj_move_foreground(s_gif_rec);
    lv_gif_restart(s_gif_rec);
}

void ui_lvgl_gif_show_done(void)
{
    if(!s_gif_done) return;
    lv_obj_clear_flag(s_gif_done, LV_OBJ_FLAG_HIDDEN);
    lv_obj_move_foreground(s_gif_done);
    lv_gif_restart(s_gif_done);
}

lv_obj_t * ui_lvgl_gif_obj_rec(void)
{
    return s_gif_rec;
}

lv_obj_t * ui_lvgl_gif_obj_done(void)
{
    return s_gif_done;
}

