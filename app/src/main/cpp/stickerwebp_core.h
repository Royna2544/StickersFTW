#ifndef STICKERSFTW_STICKERWEBP_CORE_H_
#define STICKERSFTW_STICKERWEBP_CORE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Platform-neutral libwebp conversion API.
 *
 * Pixel buffers are RGBA8888, one byte per channel in R/G/B/A order.  The
 * implementation owns no VM or Android types, so this same code can be linked
 * into the Android JNI adapter, a desktop program, or a native unit test.
 */
typedef struct SftwWebpEncoder SftwWebpEncoder;

typedef struct {
    uint8_t* bytes;
    size_t size;
} SftwWebpData;

/**
 * Output ownership: pass a zero-initialized SftwWebpData (or one previously
 * released with sftw_webp_data_clear). Successful encode/assemble calls
 * transfer an allocation to the caller; clear it exactly once. A failed call
 * leaves a non-null output in the empty {NULL, 0} state.
 */

SftwWebpEncoder* sftw_webp_encoder_create(
        int width, int height, int loop_count, int minimize_size);

int sftw_webp_encoder_configure(
        SftwWebpEncoder* encoder, float quality, int alpha_quality, int method);

int sftw_webp_encoder_add_rgba(
        SftwWebpEncoder* encoder,
        const uint8_t* rgba,
        int width,
        int height,
        int stride,
        int is_premultiplied,
        int timestamp_ms);

int sftw_webp_encoder_assemble(
        SftwWebpEncoder* encoder, int total_duration_ms, SftwWebpData* output);

void sftw_webp_encoder_delete(SftwWebpEncoder* encoder);

/**
 * Encodes one visible image once, then muxes that exact compressed bitstream
 * into two ANMF frames.  Unlike WebPAnimEncoder, WebPMux does not coalesce
 * identical frames.  The result is structurally animated while both decoded
 * frames remain pixel-identical.
 */
int sftw_webp_encode_repeated_rgba(
        const uint8_t* rgba,
        int width,
        int height,
        int stride,
        int is_premultiplied,
        int frame_duration_ms,
        int loop_count,
        float quality,
        int alpha_quality,
        int method,
        SftwWebpData* output);

void sftw_webp_data_clear(SftwWebpData* data);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // STICKERSFTW_STICKERWEBP_CORE_H_
