/**
 * @file sonya_ble.c
 * @brief BLE GATT server for Sonya Watch
 */

#include "sonya_ble.h"
#include "protocol.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "os/os_mbuf.h"
#include <string.h>

static const char *TAG = "sonya_ble";

/* SONYA 128-bit UUIDs */
static const ble_uuid128_t sonya_svc_uuid =
    BLE_UUID128_INIT(SONYA_SVC_UUID);
static const ble_uuid128_t sonya_rx_uuid =
    BLE_UUID128_INIT(SONYA_RX_UUID);
static const ble_uuid128_t sonya_tx_uuid =
    BLE_UUID128_INIT(SONYA_TX_UUID);

static uint16_t tx_val_handle;
static uint16_t conn_handle = BLE_HS_CONN_HANDLE_NONE;
static sonya_ble_rx_cb_t rx_cb;
static void *rx_arg;
static char device_name[32];
static uint16_t tx_seq;
static uint8_t tx_queue[256];

#define TX_QUEUE_MAX (sizeof(tx_queue) - PROTO_FRAME_HEADER_SIZE)
// Pacing is important: back-to-back notifications can exhaust NimBLE mbufs on ESP32-S3,
// causing ble_hs_mbuf_from_flat() to fail and the recording to end early.
#define BLE_NOTIFY_PACE_MS 12
// With audio, transient mbuf pressure is normal. Give NimBLE time to reclaim buffers.
#define BLE_NOTIFY_RETRY_MAX 200
#define BLE_NOTIFY_RETRY_DELAY_MS 10

static int gatt_access(uint16_t conn, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg);

static const struct ble_gatt_svc_def sonya_svc_defs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &sonya_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &sonya_rx_uuid.u,
                .access_cb = gatt_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &sonya_tx_uuid.u,
                .access_cb = gatt_access,
                .val_handle = &tx_val_handle,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
            },
            { 0 }
        },
    },
    { 0 }
};

static int gatt_access(uint16_t conn, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_WRITE_CHR:
        if (ble_uuid_cmp(ctxt->chr->uuid, &sonya_rx_uuid.u) == 0) {
            uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
            if (len > 0 && len <= 128 && rx_cb) {
                uint8_t buf[128];
                len = (uint16_t)(len > sizeof(buf) ? sizeof(buf) : len);
                if (ble_hs_mbuf_to_flat(ctxt->om, buf, len, NULL) == 0) {
                    rx_cb(buf, len, rx_arg);
                }
            }
        }
        return 0;

    case BLE_GATT_ACCESS_OP_READ_CHR:
        if (ble_uuid_cmp(ctxt->chr->uuid, &sonya_tx_uuid.u) == 0) {
            return os_mbuf_append(ctxt->om, "", 0);
        }
        return BLE_ATT_ERR_UNLIKELY;

    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static int start_advertising(void);

static void on_connect(struct ble_gap_event *event, void *arg)
{
    conn_handle = event->connect.conn_handle;
    ESP_LOGI(TAG, "BLE connected, conn_handle=%d", conn_handle);
}

static void on_disconnect(struct ble_gap_event *event, void *arg)
{
    conn_handle = BLE_HS_CONN_HANDLE_NONE;
    ESP_LOGI(TAG, "BLE disconnected, reason=%d", event->disconnect.reason);
    start_advertising();
}

static void on_adv_complete(struct ble_gap_event *event, void *arg)
{
    if (event->adv_complete.reason == BLE_HS_EDONE) {
        start_advertising();
    }
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        on_connect(event, arg);
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        on_disconnect(event, arg);
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        on_adv_complete(event, arg);
        break;
    default:
        break;
    }
    return 0;
}

static int start_advertising(void)
{
    struct ble_hs_adv_fields fields;
    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)device_name;
    fields.name_len = strlen(device_name);
    fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc) {
        ESP_LOGE(TAG, "adv_set_fields err %d", rc);
        return rc;
    }

    struct ble_gap_adv_params adv_params;
    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(BLE_OWN_ADDR_PUBLIC, NULL, BLE_HS_FOREVER,
                           &adv_params, gap_event, NULL);
    if (rc) {
        ESP_LOGE(TAG, "adv start err %d", rc);
        return rc;
    }
    ESP_LOGI(TAG, "BLE advertising started, name=%s", device_name);
    return 0;
}

static void on_sync(void)
{
    start_advertising();
}

static void on_reset(int reason)
{
    ESP_LOGI(TAG, "BLE reset, reason=%d", reason);
}

static void host_task(void *arg)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

int sonya_ble_init(const char *name, sonya_ble_rx_cb_t cb, void *arg)
{
    if (name) {
        strncpy(device_name, name, sizeof(device_name) - 1);
        device_name[sizeof(device_name) - 1] = '\0';
    } else {
        strcpy(device_name, "SONYA-WATCH");
    }
    rx_cb = cb;
    rx_arg = arg;
    tx_seq = 0;

    /* NimBLE port must be initialized FIRST (creates host mutex etc.) */
    int rc = nimble_port_init();
    if (rc) return rc;

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    ble_svc_gap_device_name_set(device_name);
    ble_svc_gap_init();
    ble_svc_gatt_init();

    rc = ble_gatts_count_cfg(sonya_svc_defs);
    if (rc) return rc;
    rc = ble_gatts_add_svcs(sonya_svc_defs);
    if (rc) return rc;

    nimble_port_freertos_init(host_task);

    ESP_LOGI(TAG, "BLE init done");
    return 0;
}

static int send_notify(uint16_t conn, const uint8_t *data, uint16_t len)
{
    if (conn == BLE_HS_CONN_HANDLE_NONE) return -1;
    int last_rc = 0;
    bool had_om_alloc_fail = false;
    for (int attempt = 0; attempt < BLE_NOTIFY_RETRY_MAX; attempt++) {
        if (conn_handle == BLE_HS_CONN_HANDLE_NONE) return -1;

        struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
        if (!om) {
            had_om_alloc_fail = true;
            vTaskDelay(pdMS_TO_TICKS(BLE_NOTIFY_RETRY_DELAY_MS));
            continue;
        }

        // ble_gatts_notify_custom consumes om regardless of success/failure.
        // Do NOT free om after this call.
        int rc = ble_gatts_notify_custom(conn, tx_val_handle, om);
        if (rc == 0) return 0;

        if (rc == BLE_HS_ENOMEM || rc == BLE_HS_EBUSY) {
            last_rc = rc;
            vTaskDelay(pdMS_TO_TICKS(BLE_NOTIFY_RETRY_DELAY_MS));
            continue;
        }

        ESP_LOGE(TAG, "notify err %d", rc);
        return rc;
    }
    ESP_LOGE(TAG, "notify retry exceeded (om_fail=%d last_rc=%d)", had_om_alloc_fail ? 1 : 0, last_rc);
    return -2;
}

int sonya_ble_tx_send(const uint8_t *data, size_t len)
{
    if (!data || len == 0) return -1;
    uint16_t chunk_max = CONFIG_CHUNK_SIZE;
    if (chunk_max > 180) chunk_max = 180;

    size_t offset = 0;
    while (offset < len && conn_handle != BLE_HS_CONN_HANDLE_NONE) {
        uint16_t chunk_len = (uint16_t)((len - offset) > chunk_max ? chunk_max : (len - offset));
        size_t frame_size = proto_build_frame(tx_queue, sizeof(tx_queue),
                                               PROTO_AUDIO_CHUNK, tx_seq++,
                                               data + offset, (uint16_t)chunk_len);
        if (frame_size == 0) return -2;
        int rc = send_notify(conn_handle, tx_queue, (uint16_t)frame_size);
        if (rc) return rc;
        offset += chunk_len;
        vTaskDelay(pdMS_TO_TICKS(BLE_NOTIFY_PACE_MS));
    }
    if (offset != len) {
        // Disconnected mid-transfer. Tell the caller so it can abort recording cleanly.
        return -3;
    }
    return 0;
}

static int send_frame(uint8_t type, const uint8_t *payload, uint16_t plen)
{
    // Must fit any single-frame payload we send.
    // With ATT_MTU=256, max notify value len is 253 bytes. Our frame header is 5 bytes,
    // so max payload is 248 bytes.
    uint8_t buf[PROTO_FRAME_HEADER_SIZE + 248];
    size_t sz = proto_build_frame(buf, sizeof(buf), type, tx_seq++, payload, plen);
    if (sz == 0) return -1;
    return send_notify(conn_handle, buf, (uint16_t)sz);
}

int sonya_ble_send_frame(uint8_t type, const uint8_t *payload, uint16_t plen)
{
    // For large payloads caller should use dedicated data path; keep this simple.
    if (plen > 248) return -1;
    return send_frame(type, payload, plen);
}

int sonya_ble_send_evt_wake(void)
{
    return send_frame(PROTO_EVT_WAKE, NULL, 0);
}

int sonya_ble_send_evt_rec_start(void)
{
    return send_frame(PROTO_EVT_REC_START, NULL, 0);
}

int sonya_ble_send_evt_rec_end(void)
{
    return send_frame(PROTO_EVT_REC_END, NULL, 0);
}

int sonya_ble_send_evt_error(const char *msg)
{
    if (!msg) return -1;
    size_t len = strlen(msg);
    if (len > 64) len = 64;
    return send_frame(PROTO_EVT_ERROR, (const uint8_t *)msg, (uint16_t)len);
}

bool sonya_ble_is_connected(void)
{
    return conn_handle != BLE_HS_CONN_HANDLE_NONE;
}
