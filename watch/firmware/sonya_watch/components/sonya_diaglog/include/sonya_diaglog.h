#pragma once

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Persistent ring log in NVS (for "what happened before reboot/poweroff").
// Stores short lines, up to DIAGLOG_CAP entries (cyclic).

int sonya_diaglog_init(void);
int sonya_diaglog_add(const char *tag, const char *msg);
int sonya_diaglog_addf(const char *tag, const char *fmt, ...);

// Prints last max_lines to ESP_LOGI("diaglog", ...). Pass 0 to dump full ring.
void sonya_diaglog_dump(size_t max_lines);

#ifdef __cplusplus
}
#endif

