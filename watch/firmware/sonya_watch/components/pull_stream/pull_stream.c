#include "pull_stream.h"
#include "rec_store.h"
#include "sonya_ble.h"
#include "protocol.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include <string.h>

static const char *TAG = "pull_stream";

#define AUDIO_FRAME_MAX 242
#define STREAM_STACK    8192
#define QUEUE_LEN       4

typedef struct {
    uint16_t rec_id;
    uint32_t off;
    uint16_t want_len;
} get_req_t;

static QueueHandle_t s_queue;
static TaskHandle_t  s_task;

static volatile uint16_t s_live_id;
static volatile bool     s_live_active;
static volatile bool     s_live_stop;

/* ---- send one AUDIO_DATA frame ---- */

static int send_audio_frame(uint16_t rec_id, uint32_t off, const uint8_t *pcm, int pcm_len)
{
    uint8_t payload[2 + 4 + AUDIO_FRAME_MAX];
    payload[0] = (uint8_t)(rec_id & 0xFF);
    payload[1] = (uint8_t)(rec_id >> 8);
    payload[2] = (uint8_t)(off & 0xFF);
    payload[3] = (uint8_t)((off >> 8)  & 0xFF);
    payload[4] = (uint8_t)((off >> 16) & 0xFF);
    payload[5] = (uint8_t)((off >> 24) & 0xFF);
    memcpy(payload + 6, pcm, (size_t)pcm_len);
    return sonya_ble_send_frame(PROTO_AUDIO_DATA, payload, (uint16_t)(6 + pcm_len));
}

/* ---- live streaming (runs in stream_task) ---- */

static void live_loop(void)
{
    uint16_t rid = s_live_id;
    uint32_t sent = 0;
    uint32_t t0 = (uint32_t)esp_log_timestamp();
    int frames = 0;
    uint8_t buf[AUDIO_FRAME_MAX];

    ESP_LOGI(TAG, "LIVE start rec_id=%u", (unsigned)rid);

    while (sonya_ble_is_connected()) {
        int total = rec_store_total_bytes();
        int avail = total - (int)sent;

        bool stopping = s_live_stop;

        if (avail >= AUDIO_FRAME_MAX || (stopping && avail > 0)) {
            int chunk = avail > AUDIO_FRAME_MAX ? AUDIO_FRAME_MAX : avail;
            int rd = rec_store_read(sent, buf, (size_t)chunk);
            if (rd <= 0) {
                vTaskDelay(pdMS_TO_TICKS(5));
                continue;
            }
            int rc = send_audio_frame(rid, sent, buf, rd);
            if (rc) {
                vTaskDelay(pdMS_TO_TICKS(30));
                continue;
            }
            sent += (uint32_t)rd;
            frames++;
            vTaskDelay(pdMS_TO_TICKS(8));
        } else if (stopping) {
            break;
        } else {
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }

    uint32_t dt = (uint32_t)esp_log_timestamp() - t0;
    UBaseType_t hwm = uxTaskGetStackHighWaterMark(s_task);
    ESP_LOGI(TAG, "LIVE end: frames=%d bytes=%lu dt=%lums stack_free=%u",
             frames, (unsigned long)sent, (unsigned long)dt, (unsigned)hwm);

    s_live_active = false;
}

/* ---- pull window (responds to GET, runs in stream_task) ---- */

static void pull_window(const get_req_t *req)
{
    uint16_t remaining = req->want_len;
    uint32_t cur = req->off;
    uint32_t t0 = (uint32_t)esp_log_timestamp();
    int frames = 0;
    int bytes_sent = 0;
    uint8_t buf[AUDIO_FRAME_MAX];

    while (remaining > 0 && sonya_ble_is_connected()
           && cur < (uint32_t)rec_store_total_bytes()) {
        get_req_t newer;
        if (xQueuePeek(s_queue, &newer, 0) == pdTRUE) break;

        int chunk = remaining > AUDIO_FRAME_MAX ? AUDIO_FRAME_MAX : remaining;
        int rd = rec_store_read(cur, buf, (size_t)chunk);
        if (rd <= 0) break;

        int rc = send_audio_frame(req->rec_id, cur, buf, rd);
        if (rc) {
            vTaskDelay(pdMS_TO_TICKS(30));
            rc = send_audio_frame(req->rec_id, cur, buf, rd);
            if (rc) break;
        }
        frames++;
        bytes_sent += rd;
        cur += (uint32_t)rd;
        remaining = (uint16_t)(remaining - (uint16_t)rd);
        vTaskDelay(pdMS_TO_TICKS(8));
    }

    uint32_t dt = (uint32_t)esp_log_timestamp() - t0;
    ESP_LOGI(TAG, "PULL frames=%d bytes=%d dt=%lums off0=%lu off1=%lu",
             frames, bytes_sent, (unsigned long)dt,
             (unsigned long)req->off, (unsigned long)cur);
}

/* ---- task ---- */

static void stream_task(void *arg)
{
    (void)arg;
    for (;;) {
        if (s_live_active) {
            live_loop();
            continue;
        }

        get_req_t req;
        if (xQueueReceive(s_queue, &req, pdMS_TO_TICKS(50)) == pdTRUE) {
            pull_window(&req);
        }
    }
}

/* ---- public API ---- */

esp_err_t pull_stream_init(void)
{
    s_queue = xQueueCreate(QUEUE_LEN, sizeof(get_req_t));
    if (!s_queue) return ESP_ERR_NO_MEM;

    BaseType_t rc = xTaskCreate(stream_task, "pull_stream", STREAM_STACK, NULL, 5, &s_task);
    if (rc != pdPASS) return ESP_ERR_NO_MEM;

    ESP_LOGI(TAG, "init ok");
    return ESP_OK;
}

void pull_stream_start_live(uint16_t rec_id)
{
    s_live_id = rec_id;
    s_live_stop = false;
    s_live_active = true;
    ESP_LOGI(TAG, "start_live rec_id=%u", (unsigned)rec_id);
}

void pull_stream_stop_live(void)
{
    if (!s_live_active) return;
    s_live_stop = true;
    while (s_live_active) {
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    ESP_LOGI(TAG, "stop_live done");
}

void pull_stream_handle_get(uint16_t rec_id, uint32_t off, uint16_t want_len)
{
    if (!sonya_ble_is_connected()) return;
    ESP_LOGI(TAG, "RX: GET rec_id=%u off=%lu want_len=%u",
             (unsigned)rec_id, (unsigned long)off, (unsigned)want_len);

    if (rec_id != rec_store_cur_id() || rec_store_total_bytes() <= 0) {
        sonya_ble_send_evt_error("NO_REC");
        return;
    }
    if (off >= (uint32_t)rec_store_total_bytes()) {
        sonya_ble_send_evt_error("EOF");
        return;
    }
    get_req_t req = { .rec_id = rec_id, .off = off, .want_len = want_len };
    xQueueReset(s_queue);
    xQueueSend(s_queue, &req, 0);
}

void pull_stream_handle_done(uint16_t rec_id)
{
    if (rec_id == rec_store_cur_id()) {
        ESP_LOGI(TAG, "RX: DONE rec_id=%u -> free", (unsigned)rec_id);
        xQueueReset(s_queue);
        rec_store_clear();
    }
}
