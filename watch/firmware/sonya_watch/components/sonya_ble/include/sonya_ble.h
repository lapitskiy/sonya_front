/**
 * @file sonya_ble.h
 * @brief BLE GATT server for Sonya Watch
 *
 * Device advertises as "SONYA-WATCH"
 * Service SONYA (128-bit UUID)
 * RX: Write/WriteNoRsp - commands from phone
 * TX: Notify - events/data to phone
 */

#pragma once

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

/* SONYA service UUID: 12345678-1234-5678-1234-56789abcdef0 */
#define SONYA_SVC_UUID  0x12, 0x34, 0x56, 0x78, 0x12, 0x34, 0x56, 0x78, \
                        0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xf0
#define SONYA_RX_UUID   0x12, 0x34, 0x56, 0x78, 0x12, 0x34, 0x56, 0x78, \
                        0x12, 0x34, 0x56, 0x79, 0x9a, 0xbc, 0xde, 0xf0
#define SONYA_TX_UUID   0x12, 0x34, 0x56, 0x78, 0x12, 0x34, 0x56, 0x78, \
                        0x12, 0x34, 0x56, 0x7a, 0x9a, 0xbc, 0xde, 0xf0

typedef void (*sonya_ble_rx_cb_t)(const uint8_t *data, uint16_t len, void *arg);

/**
 * @brief Initialize BLE GATT server and start advertising
 * @param device_name Advertising name (e.g. "SONYA-WATCH")
 * @param rx_cb Callback for RX characteristic writes
 * @param rx_arg User arg for rx_cb
 * @return 0 on success, negative on error
 */
int sonya_ble_init(const char *device_name, sonya_ble_rx_cb_t rx_cb, void *rx_arg);

/**
 * @brief Send data via TX notify (queued, respects CHUNK_SIZE)
 * @param data Data to send
 * @param len Length
 * @return 0 on success, negative on error
 */
int sonya_ble_tx_send(const uint8_t *data, size_t len);

/**
 * @brief Send a single protocol frame (type + payload) as one notify
 *
 * Unlike sonya_ble_tx_send(), this does not re-chunk into AUDIO_CHUNK frames.
 * Useful for pull-based protocols where the application chooses the frame type.
 */
int sonya_ble_send_frame(uint8_t type, const uint8_t *payload, uint16_t plen);

/**
 * @brief Check if a client is connected
 */
bool sonya_ble_is_connected(void);

/**
 * @brief Send protocol events (convenience helpers)
 */
int sonya_ble_send_evt_wake(void);
int sonya_ble_send_evt_rec_start(void);
int sonya_ble_send_evt_rec_end(void);
int sonya_ble_send_evt_error(const char *msg);

// Legacy v0 helper used by current app_main; kept for compatibility.
// Prefer sonya_ble_send_frame for custom protocol types.
