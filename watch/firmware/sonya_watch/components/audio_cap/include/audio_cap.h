/**
 * @file audio_cap.h
 * @brief I2S microphone capture, ring buffer, fixed-duration record
 *
 * 16kHz mono 16-bit PCM.
 * Ring buffer ~2 sec for future prebuffer.
 * Record segment = REC_SECONDS into RAM buffer.
 */

#pragma once

#include <stdint.h>
#include <stddef.h>

/**
 * @brief Initialize I2S and ring buffer
 * @return 0 on success
 *
 * TODO: Waveshare uses ES7210 codec for dual mic - may need I2C init.
 * GPIO from Kconfig: I2S_BCK_GPIO, I2S_WS_GPIO, I2S_DIN_GPIO, I2S_MCLK_GPIO.
 */
int audio_cap_init(void);

/**
 * @brief Start continuous capture into ring buffer
 */
int audio_cap_start(void);

/**
 * @brief Stop capture
 */
void audio_cap_stop(void);

/**
 * @brief Drop any currently buffered PCM from the ring buffer.
 *
 * Useful at REC_START to avoid starting a segment with pre-wake audio and
 * inadvertently cutting off the end.
 */
void audio_cap_flush(void);

/**
 * @brief Read up to max_len bytes from capture ring buffer
 * @param buf Output buffer
 * @param max_len Max bytes to copy
 * @param timeout_ms Wait timeout (ms)
 * @return Bytes read (0 if timeout), or negative on error
 */
int audio_cap_read(uint8_t *buf, size_t max_len, uint32_t timeout_ms);

/**
 * @brief Record fixed duration into provided buffer
 *
 * NOTE: This API buffers whole segment in RAM. Prefer streaming via audio_cap_read()
 * when RAM is limited.
 */
int audio_cap_record_segment(uint8_t *buf, size_t buf_size, int rec_seconds);
