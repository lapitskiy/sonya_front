#pragma once

#include <stdbool.h>

typedef void (*watch_idle_off_stop_audio_fn_t)(void *arg);

void watch_idle_off_tick(bool recording,
                         bool audio_streaming,
                         watch_idle_off_stop_audio_fn_t stop_audio,
                         void *stop_audio_arg);
