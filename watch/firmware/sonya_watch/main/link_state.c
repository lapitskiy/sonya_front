#include "link_state.h"

#include "esp_log.h"
#include "sonya_diaglog.h"

static const char *TAG = "link_state";

static volatile link_state_t s_state = LINK_STATE_DISCONNECTED;
static volatile bool s_time_synced = false;

void link_state_init(void)
{
    s_state = LINK_STATE_DISCONNECTED;
    s_time_synced = false;
}

bool link_state_update_connected(bool connected)
{
    const bool was_connected = (s_state != LINK_STATE_DISCONNECTED);
    if (connected == was_connected) return false;

    if (connected) {
        s_state = LINK_STATE_CONNECTED;
        ESP_LOGI(TAG, "state -> CONNECTED");
        sonya_diaglog_add("link", "connected");
    } else {
        s_state = LINK_STATE_DISCONNECTED;
        s_time_synced = false;
        ESP_LOGI(TAG, "state -> DISCONNECTED");
        sonya_diaglog_add("link", "disconnected");
    }
    return true;
}

void link_state_mark_proto_ready(const char *reason)
{
    if (s_state == LINK_STATE_DISCONNECTED) return;
    if (s_state != LINK_STATE_PROTO_READY) {
        ESP_LOGI(TAG, "state -> PROTO_READY (%s)", reason ? reason : "n/a");
        sonya_diaglog_addf("link", "ready %s", reason ? reason : "n/a");
    }
    s_state = LINK_STATE_PROTO_READY;
}

void link_state_mark_time_synced(void)
{
    s_time_synced = true;
}

link_state_t link_state_get(void)
{
    return s_state;
}

bool link_state_is_connected(void)
{
    return s_state != LINK_STATE_DISCONNECTED;
}

bool link_state_is_proto_ready(void)
{
    return s_state == LINK_STATE_PROTO_READY;
}

bool link_state_is_time_synced(void)
{
    return s_time_synced;
}

const char *link_state_name(link_state_t state)
{
    switch (state) {
    case LINK_STATE_DISCONNECTED: return "DISCONNECTED";
    case LINK_STATE_CONNECTED: return "CONNECTED";
    case LINK_STATE_PROTO_READY: return "PROTO_READY";
    default: return "UNKNOWN";
    }
}
