#include "sonya_diaglog.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "nvs.h"
#include "nvs_flash.h"

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static const char *TAG = "diaglog";

// Keep small to reduce NVS writes/size.
#define DIAGLOG_CAP 200
#define DIAGLOG_LINE_MAX 120

static nvs_handle_t s_nvs = 0;
static SemaphoreHandle_t s_mu = NULL;

static void key_for_idx(uint32_t i, char out[8])
{
    // l000..l199
    uint32_t slot = i % DIAGLOG_CAP;
    snprintf(out, 8, "l%03u", (unsigned)slot);
}

static int with_lock(TickType_t to)
{
    if (!s_mu) return -1;
    return (xSemaphoreTake(s_mu, to) == pdTRUE) ? 0 : -1;
}

static void unlock(void)
{
    if (s_mu) xSemaphoreGive(s_mu);
}

int sonya_diaglog_init(void)
{
    if (s_mu == NULL) {
        s_mu = xSemaphoreCreateMutex();
        if (!s_mu) return -1;
    }
    if (s_nvs != 0) return 0;
    esp_err_t err = nvs_open("diaglog", NVS_READWRITE, &s_nvs);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open diaglog err=%d", (int)err);
        s_nvs = 0;
        return -2;
    }
    return 0;
}

int sonya_diaglog_add(const char *tag, const char *msg)
{
    if (!tag) tag = "x";
    if (!msg) msg = "";
    if (s_nvs == 0) return -10;

    if (with_lock(pdMS_TO_TICKS(100)) != 0) return -11;

    uint32_t idx = 0;
    esp_err_t err = nvs_get_u32(s_nvs, "idx", &idx);
    if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND) {
        unlock();
        return -12;
    }

    char line[DIAGLOG_LINE_MAX];
    uint32_t ms = (uint32_t)(esp_timer_get_time() / 1000);
    // Keep it simple; line ends up as: "123456 sys something"
    snprintf(line, sizeof(line), "%u %s %s", (unsigned)ms, tag, msg);

    char key[8];
    key_for_idx(idx, key);
    err = nvs_set_str(s_nvs, key, line);
    if (err != ESP_OK) {
        unlock();
        return -13;
    }
    err = nvs_set_u32(s_nvs, "idx", idx + 1U);
    if (err != ESP_OK) {
        unlock();
        return -14;
    }
    err = nvs_commit(s_nvs);
    unlock();
    return (err == ESP_OK) ? 0 : -15;
}

int sonya_diaglog_addf(const char *tag, const char *fmt, ...)
{
    if (!fmt) return -1;
    char msg[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    return sonya_diaglog_add(tag, msg);
}

void sonya_diaglog_dump(size_t max_lines)
{
    if (s_nvs == 0) {
        ESP_LOGW(TAG, "dump skipped: not init");
        return;
    }
    if (with_lock(pdMS_TO_TICKS(200)) != 0) {
        ESP_LOGW(TAG, "dump skipped: lock");
        return;
    }

    uint32_t idx = 0;
    esp_err_t err = nvs_get_u32(s_nvs, "idx", &idx);
    if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND) {
        unlock();
        return;
    }

    uint32_t have = idx;
    if (have > DIAGLOG_CAP) have = DIAGLOG_CAP;
    if (max_lines == 0 || max_lines > have) max_lines = have;
    uint32_t start = (idx >= (uint32_t)max_lines) ? (idx - (uint32_t)max_lines) : 0;

    ESP_LOGI(TAG, "dump last=%u (idx=%u)", (unsigned)max_lines, (unsigned)idx);

    for (uint32_t i = start; i < idx; i++) {
        char key[8];
        key_for_idx(i, key);
        size_t need = 0;
        err = nvs_get_str(s_nvs, key, NULL, &need);
        if (err != ESP_OK || need == 0 || need > 256) continue;
        char buf[256];
        size_t cap = sizeof(buf);
        if (need > cap) need = cap;
        err = nvs_get_str(s_nvs, key, buf, &need);
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "%s", buf);
        }
    }

    unlock();
}

