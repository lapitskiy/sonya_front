/**
 * @file wake_engine.h
 * @brief Wake detection engine interface (v0 stub)
 *
 * TODO: Replace with Porcupine/TFLM for keyword spotting.
 * v0: CMD (RX "START"), BUTTON (GPIO), or RMS (audio energy).
 */

#pragma once

#include <stdint.h>
#include <stdbool.h>

typedef enum {
    WAKE_MODE_CMD = 0,
    WAKE_MODE_BUTTON,
    WAKE_MODE_RMS,
    WAKE_MODE_WWE,
    WAKE_MODE_MULTI, /* BUTTON + WWE */
} wake_mode_t;

/**
 * @brief Initialize wake engine (runtime-selected mode)
 * @param mode Wake mode to use
 * @return 0 on success
 */
int wake_init(wake_mode_t mode);

/**
 * @brief Poll for wake or wait (blocking with timeout)
 * @param timeout_ms Max wait in ms, 0 = non-blocking poll
 * @return true if wake detected
 */
bool wake_poll_or_wait(uint32_t timeout_ms);

/**
 * @brief Get last wake confidence (0..100, stub returns 100)
 */
uint8_t wake_get_confidence(void);

/**
 * @brief Whether the last wake trigger came from the button.
 *
 * Useful in WAKE_MODE_MULTI to choose between hold-to-record (button) and
 * fixed-duration record (WWE/CMD).
 */
bool wake_triggered_by_button(void);

/**
 * @brief Notify wake engine of RX command (for CMD mode)
 * @param cmd Command string (e.g. "START")
 */
void wake_on_rx_cmd(const char *cmd);

/**
 * @brief Temporarily ignore wake triggers (ms from now).
 *
 * Used to prevent re-trigger while we're recording.
 * Pass 0 to cancel suspension immediately.
 */
void wake_suspend_ms(uint32_t ms);
