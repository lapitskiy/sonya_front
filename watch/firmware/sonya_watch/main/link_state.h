#pragma once

#include <stdbool.h>

typedef enum {
    LINK_STATE_DISCONNECTED = 0,
    LINK_STATE_CONNECTED,
    LINK_STATE_PROTO_READY,
} link_state_t;

void link_state_init(void);

// Returns true when the physical BLE connection state changed.
bool link_state_update_connected(bool connected);

void link_state_mark_proto_ready(const char *reason);
void link_state_mark_time_synced(void);

link_state_t link_state_get(void);
bool link_state_is_connected(void);
bool link_state_is_proto_ready(void);
bool link_state_is_time_synced(void);
const char *link_state_name(link_state_t state);
