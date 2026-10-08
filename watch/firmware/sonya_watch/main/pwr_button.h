#pragma once

#include <stdbool.h>

/**
 * Owner of "2 short PWR presses -> request forced power-off" detection.
 *
 * Only decides timing (is this the 2nd short press within the double-press window?).
 * Does not touch BLE, screen, audio or PMU directly — the caller (app_main) is responsible
 * for actually powering off via watch_idle_off_force() when this returns true.
 */

/** Reset double-press window state. Call once at boot. */
void pwr_button_init(void);

/**
 * Call periodically (e.g. every ~100ms from the main loop) with the latest raw short-press
 * event polled from the PMU (sonya_board_pmu_poll_short_press()).
 *
 * Returns true exactly once, on the 2nd short press landing within the double-press window
 * after the 1st one. A 1st press (or two presses too far apart) returns false.
 */
bool pwr_button_tick(bool short_press_event);
