#pragma once

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

void     rec_store_clear(void);
uint16_t rec_store_begin(void);

bool     rec_store_append(const uint8_t *data, size_t len);
bool     rec_store_alloc_block(void);
uint8_t *rec_store_tail_ptr(size_t *out_room);
void     rec_store_tail_advance(size_t n);

int      rec_store_total_bytes(void);
uint32_t rec_store_crc32(void);
uint16_t rec_store_commit(void);
uint16_t rec_store_cur_id(void);

int      rec_store_read(uint32_t offset, uint8_t *dst, size_t max_len);
