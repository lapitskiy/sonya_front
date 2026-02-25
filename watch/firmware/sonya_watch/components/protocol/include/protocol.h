/**
 * @file protocol.h
 * @brief Binary protocol over BLE GATT
 *
 * FRAME: [type:uint8][seq:uint16][len:uint16][payload]
 */

#pragma once

#include <stdint.h>
#include <stddef.h>

#define PROTO_FRAME_HEADER_SIZE 5

/* Frame types */
#define PROTO_EVT_WAKE      0x01
#define PROTO_EVT_REC_START 0x02
#define PROTO_EVT_REC_END   0x03
#define PROTO_AUDIO_CHUNK   0x10
#define PROTO_EVT_ERROR     0x11
// Audio data sent in response to GET requests (payload contains offset)
#define PROTO_AUDIO_DATA    0x12

typedef struct {
    uint8_t  type;
    uint16_t seq;
    uint16_t len;
    uint8_t  payload[];
} proto_frame_t;

/**
 * @brief Build a frame into buffer
 * @param buf Output buffer (must be at least PROTO_FRAME_HEADER_SIZE + payload_len)
 * @param buf_size Total buffer size
 * @param type Frame type
 * @param seq Sequence number
 * @param payload Payload data (may be NULL if len==0)
 * @param payload_len Payload length
 * @return Total frame size, or 0 on error
 */
size_t proto_build_frame(uint8_t *buf, size_t buf_size,
                         uint8_t type, uint16_t seq,
                         const uint8_t *payload, uint16_t payload_len);

/**
 * @brief Parse frame header from buffer
 * @param buf Input buffer
 * @param buf_len Buffer length
 * @param out_type Output frame type
 * @param out_seq Output sequence number
 * @param out_len Output payload length
 * @return Total frame size (header+payload), or 0 if incomplete/invalid
 */
size_t proto_parse_header(const uint8_t *buf, size_t buf_len,
                          uint8_t *out_type, uint16_t *out_seq, uint16_t *out_len);

/* ASCII command types from RX (v0 test mode) */
typedef enum {
    PROTO_CMD_NONE = 0,
    PROTO_CMD_PING,
    PROTO_CMD_REC,
    PROTO_CMD_SETREC,
    PROTO_CMD_GET,
    PROTO_CMD_DONE,
} proto_cmd_t;

/**
 * @brief Parse ASCII command from RX buffer
 * @param buf Raw bytes from RX characteristic
 * @param len Length
 * @param out_rec_sec Output: new rec seconds for SETREC (1..10)
 * @param out_rec_id Output: rec id for GET/DONE
 * @param out_offset Output: offset for GET
 * @param out_len Output: requested length for GET
 * @return Parsed command type
 */
proto_cmd_t proto_parse_rx_cmd(const uint8_t *buf, size_t len,
                               int *out_rec_sec,
                               uint16_t *out_rec_id,
                               uint32_t *out_offset,
                               uint16_t *out_len);
