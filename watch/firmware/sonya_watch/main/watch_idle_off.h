#pragma once

#include <stdbool.h>

typedef void (*watch_idle_off_stop_audio_fn_t)(void *arg);

void watch_idle_off_tick(bool recording,
                         bool audio_streaming,
                         watch_idle_off_stop_audio_fn_t stop_audio,
                         void *stop_audio_arg);

/**
 * Immediately power off, bypassing the idle timer (e.g. triggered by a user action such as
 * a PWR double-press). Still refuses to power off while recording or with live USB/VBUS,
 * for the same reasons as watch_idle_off_tick() — this is NOT a separate auto-off policy,
 * just a different trigger for the same single power-off path.
 */
void watch_idle_off_force(const char *reason,
                          bool recording,
                          bool audio_streaming,
                          watch_idle_off_stop_audio_fn_t stop_audio,
                          void *stop_audio_arg);
