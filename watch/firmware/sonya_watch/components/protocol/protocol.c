/**
 * @file protocol.c
 * @brief Binary protocol implementation
 */

#include "protocol.h"
#include <string.h>
#include <stdlib.h>

size_t proto_build_frame(uint8_t *buf, size_t buf_size,
                         uint8_t type, uint16_t seq,
                         const uint8_t *payload, uint16_t payload_len)
{
    if (buf == NULL || buf_size < PROTO_FRAME_HEADER_SIZE + payload_len) {
        return 0;
    }
    buf[0] = type;
    buf[1] = (uint8_t)(seq & 0xFF);
    buf[2] = (uint8_t)(seq >> 8);
    buf[3] = (uint8_t)(payload_len & 0xFF);
    buf[4] = (uint8_t)(payload_len >> 8);
    if (payload && payload_len > 0) {
        memcpy(buf + PROTO_FRAME_HEADER_SIZE, payload, payload_len);
    }
    return PROTO_FRAME_HEADER_SIZE + payload_len;
}

size_t proto_parse_header(const uint8_t *buf, size_t buf_len,
                          uint8_t *out_type, uint16_t *out_seq, uint16_t *out_len)
{
    if (buf == NULL || buf_len < PROTO_FRAME_HEADER_SIZE) {
        return 0;
    }
    uint16_t plen = (uint16_t)buf[3] | ((uint16_t)buf[4] << 8);
    size_t total = PROTO_FRAME_HEADER_SIZE + plen;
    if (buf_len < total) {
        return 0;
    }
    if (out_type) *out_type = buf[0];
    if (out_seq) *out_seq = (uint16_t)buf[1] | ((uint16_t)buf[2] << 8);
    if (out_len) *out_len = plen;
    return total;
}

proto_cmd_t proto_parse_rx_cmd(const uint8_t *buf, size_t len,
                               int *out_rec_sec,
                               uint16_t *out_rec_id,
                               uint32_t *out_offset,
                               uint16_t *out_len)
{
    if (!buf || len == 0) return PROTO_CMD_NONE;

    char cmd[32];
    size_t n = len < sizeof(cmd) - 1 ? len : sizeof(cmd) - 1;
    memcpy(cmd, buf, n);
    cmd[n] = '\0';

    if (n >= 4 && memcmp(cmd, "PING", 4) == 0) return PROTO_CMD_PING;
    /* Accept "REC" with optional trailing newline/whitespace from BLE apps */
    if (n >= 3 && memcmp(cmd, "REC", 3) == 0) return PROTO_CMD_REC;

    if (n >= 8 && memcmp(cmd, "SETREC:", 7) == 0) {
        int v = atoi(cmd + 7);
        if (v >= 1 && v <= 10) {
            if (out_rec_sec) *out_rec_sec = v;
            return PROTO_CMD_SETREC;
        }
    }

    // GET:<recId>:<offset>:<len>
    if (n >= 4 && memcmp(cmd, "GET:", 4) == 0) {
        const char *p = cmd + 4;
        char *end = NULL;
        unsigned long rec_id = strtoul(p, &end, 10);
        if (!end || *end != ':') return PROTO_CMD_NONE;
        p = end + 1;
        unsigned long off = strtoul(p, &end, 10);
        if (!end || *end != ':') return PROTO_CMD_NONE;
        p = end + 1;
        unsigned long l = strtoul(p, &end, 10);
        if (l == 0 || l > 65535UL) return PROTO_CMD_NONE;
        if (out_rec_id) *out_rec_id = (uint16_t)rec_id;
        if (out_offset) *out_offset = (uint32_t)off;
        if (out_len) *out_len = (uint16_t)l;
        return PROTO_CMD_GET;
    }

    // DONE:<recId>
    if (n >= 5 && memcmp(cmd, "DONE:", 5) == 0) {
        const char *p = cmd + 5;
        char *end = NULL;
        unsigned long rec_id = strtoul(p, &end, 10);
        if (out_rec_id) *out_rec_id = (uint16_t)rec_id;
        return PROTO_CMD_DONE;
    }
    return PROTO_CMD_NONE;
}
