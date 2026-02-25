#pragma once

#include <stdint.h>
#include "esp_err.h"

esp_err_t pull_stream_init(void);

void pull_stream_start_live(uint16_t rec_id);
void pull_stream_stop_live(void);

void pull_stream_handle_get(uint16_t rec_id, uint32_t off, uint16_t want_len);
void pull_stream_handle_done(uint16_t rec_id);
