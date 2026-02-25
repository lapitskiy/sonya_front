#include "rec_store.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include <string.h>

static const char *TAG = "rec_store";

#define BLOCK_CAP (8 * 1024)

typedef struct block {
    struct block *next;
    size_t used;
    size_t cap;
    uint8_t data[];
} block_t;

static block_t *s_head = NULL;
static block_t *s_tail = NULL;
static int      s_bytes = 0;
static uint32_t s_crc32 = 0;
static uint16_t s_cur_id = 0;
static uint16_t s_next_id = 1;

/* ---- CRC32 ---- */
static uint32_t s_crc_tbl[256];
static bool     s_crc_inited = false;

static void crc_init(void)
{
    const uint32_t poly = 0xEDB88320U;
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int j = 0; j < 8; j++)
            c = (c & 1) ? (poly ^ (c >> 1)) : (c >> 1);
        s_crc_tbl[i] = c;
    }
    s_crc_inited = true;
}

static uint32_t crc_update(uint32_t crc, const uint8_t *d, size_t n)
{
    if (!s_crc_inited) crc_init();
    for (size_t i = 0; i < n; i++)
        crc = s_crc_tbl[(crc ^ d[i]) & 0xFF] ^ (crc >> 8);
    return crc;
}

/* ---- helpers ---- */
static block_t *alloc_block(void)
{
    size_t sz = sizeof(block_t) + BLOCK_CAP;
    block_t *b = (block_t *)heap_caps_malloc(sz, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!b) b = (block_t *)heap_caps_malloc(sz, MALLOC_CAP_8BIT);
    if (!b) return NULL;
    b->next = NULL;
    b->used = 0;
    b->cap  = BLOCK_CAP;
    return b;
}

/* ---- public API ---- */

void rec_store_clear(void)
{
    for (block_t *b = s_head; b; ) {
        block_t *n = b->next;
        heap_caps_free(b);
        b = n;
    }
    s_head = s_tail = NULL;
    s_bytes = 0;
    s_crc32 = 0;
    s_cur_id = 0;
}

uint16_t rec_store_begin(void)
{
    rec_store_clear();
    s_cur_id = s_next_id++;
    if (s_next_id == 0) s_next_id = 1;
    ESP_LOGI(TAG, "begin id=%u", (unsigned)s_cur_id);
    return s_cur_id;
}

bool rec_store_alloc_block(void)
{
    block_t *b = alloc_block();
    if (!b) return false;
    if (!s_head) s_head = b;
    if (s_tail) s_tail->next = b;
    s_tail = b;
    return true;
}

uint8_t *rec_store_tail_ptr(size_t *out_room)
{
    if (!s_tail || s_tail->used >= s_tail->cap) {
        if (out_room) *out_room = 0;
        return NULL;
    }
    if (out_room) *out_room = s_tail->cap - s_tail->used;
    return s_tail->data + s_tail->used;
}

void rec_store_tail_advance(size_t n)
{
    if (!s_tail) return;
    s_tail->used += n;
    s_bytes += (int)n;
}

bool rec_store_append(const uint8_t *data, size_t len)
{
    size_t off = 0;
    while (off < len) {
        if (!s_tail || s_tail->used >= s_tail->cap) {
            if (!rec_store_alloc_block()) return false;
        }
        size_t room = s_tail->cap - s_tail->used;
        size_t take = (len - off) < room ? (len - off) : room;
        memcpy(s_tail->data + s_tail->used, data + off, take);
        s_tail->used += take;
        s_bytes += (int)take;
        off += take;
    }
    return true;
}

int rec_store_total_bytes(void) { return s_bytes; }

uint32_t rec_store_crc32(void)  { return s_crc32; }
uint16_t rec_store_cur_id(void) { return s_cur_id; }

uint16_t rec_store_commit(void)
{
    uint32_t crc = 0xFFFFFFFFU;
    for (block_t *b = s_head; b; b = b->next)
        crc = crc_update(crc, b->data, b->used);
    s_crc32 = ~crc;
    if (s_cur_id == 0) {
        s_cur_id = s_next_id++;
        if (s_next_id == 0) s_next_id = 1;
    }
    ESP_LOGI(TAG, "commit id=%u bytes=%d crc32=0x%08lx",
             (unsigned)s_cur_id, s_bytes, (unsigned long)s_crc32);
    return s_cur_id;
}

int rec_store_read(uint32_t offset, uint8_t *dst, size_t max_len)
{
    if (!s_head || offset >= (uint32_t)s_bytes) return 0;

    uint32_t pos = 0;
    block_t *b = s_head;
    while (b && (pos + b->used) <= offset) {
        pos += (uint32_t)b->used;
        b = b->next;
    }

    size_t copied = 0;
    uint32_t cur = offset;
    while (b && copied < max_len && cur < (uint32_t)s_bytes) {
        uint32_t in_block = cur - pos;
        size_t avail = b->used - in_block;
        size_t need  = max_len - copied;
        size_t take  = avail < need ? avail : need;
        memcpy(dst + copied, b->data + in_block, take);
        copied += take;
        cur    += (uint32_t)take;
        if (in_block + take >= b->used) {
            pos += (uint32_t)b->used;
            b = b->next;
        }
    }
    return (int)copied;
}
