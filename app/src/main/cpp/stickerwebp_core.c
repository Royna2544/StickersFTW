#include "stickerwebp_core.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "webp/encode.h"
#include "webp/mux.h"

struct SftwWebpEncoder {
    WebPAnimEncoder* encoder;
    WebPConfig config;
    int width;
    int height;
};

static int valid_canvas_dimensions(int width, int height) {
    return width > 0 && height > 0 &&
           width <= WEBP_MAX_DIMENSION && height <= WEBP_MAX_DIMENSION;
}

static int valid_dimensions(int width, int height, int stride) {
    return valid_canvas_dimensions(width, height) && stride >= width * 4;
}

static uint8_t unpremultiply_channel(uint8_t value, uint32_t alpha) {
    const uint32_t straight = (value * 255u + alpha / 2u) / alpha;
    return (uint8_t) (straight > 255u ? 255u : straight);
}

static void unpremultiply(uint8_t* rgba, size_t pixels) {
    for (size_t i = 0; i < pixels; ++i) {
        uint8_t* pixel = rgba + i * 4;
        const uint32_t alpha = pixel[3];
        if (alpha == 0) {
            pixel[0] = pixel[1] = pixel[2] = 0;
        } else if (alpha < 255) {
            pixel[0] = unpremultiply_channel(pixel[0], alpha);
            pixel[1] = unpremultiply_channel(pixel[1], alpha);
            pixel[2] = unpremultiply_channel(pixel[2], alpha);
        }
    }
}

/** Imports a possibly-strided RGBA buffer into a WebPPicture. */
static int import_rgba(
        WebPPicture* picture,
        const uint8_t* rgba,
        int width,
        int height,
        int stride,
        int is_premultiplied) {
    if (picture == NULL || rgba == NULL || !valid_dimensions(width, height, stride)) return 0;
    if (!WebPPictureInit(picture)) return 0;

    picture->use_argb = 1;
    picture->width = width;
    picture->height = height;

    const size_t row_bytes = (size_t) width * 4;
    const size_t pixel_count = (size_t) width * (size_t) height;
    uint8_t* packed = (uint8_t*) malloc(pixel_count * 4);
    if (packed == NULL) {
        WebPPictureFree(picture);
        return 0;
    }
    for (int y = 0; y < height; ++y) {
        memcpy(packed + (size_t) y * row_bytes, rgba + (size_t) y * stride, row_bytes);
    }
    if (is_premultiplied) unpremultiply(packed, pixel_count);

    const int ok = WebPPictureImportRGBA(picture, packed, width * 4);
    free(packed);
    if (!ok) WebPPictureFree(picture);
    return ok;
}

static int configure(WebPConfig* config, float quality, int alpha_quality, int method) {
    if (config == NULL || !isfinite(quality) || !WebPConfigInit(config)) return 0;
    config->quality = quality;
    config->alpha_quality = alpha_quality;
    config->method = method;
    config->lossless = 0;
    return WebPValidateConfig(config);
}

SftwWebpEncoder* sftw_webp_encoder_create(
        int width, int height, int loop_count, int minimize_size) {
    if (!valid_canvas_dimensions(width, height)) return NULL;

    WebPAnimEncoderOptions options;
    if (!WebPAnimEncoderOptionsInit(&options)) return NULL;
    // The libwebp default is opaque white.  A sticker canvas is transparent;
    // spelling that out avoids a white flash in viewers that honor the ANIM
    // background while resetting a loop or disposing a frame.
    options.anim_params.bgcolor = 0x00000000u;
    options.anim_params.loop_count = loop_count;
    options.minimize_size = minimize_size ? 1 : 0;

    SftwWebpEncoder* state = (SftwWebpEncoder*) calloc(1, sizeof(SftwWebpEncoder));
    if (state == NULL) return NULL;
    state->encoder = WebPAnimEncoderNew(width, height, &options);
    if (state->encoder == NULL || !WebPConfigInit(&state->config)) {
        if (state->encoder != NULL) WebPAnimEncoderDelete(state->encoder);
        free(state);
        return NULL;
    }
    state->width = width;
    state->height = height;
    return state;
}

int sftw_webp_encoder_configure(
        SftwWebpEncoder* encoder, float quality, int alpha_quality, int method) {
    if (encoder == NULL || !isfinite(quality)) return 0;
    encoder->config.quality = quality;
    encoder->config.alpha_quality = alpha_quality;
    encoder->config.method = method;
    encoder->config.lossless = 0;
    return WebPValidateConfig(&encoder->config);
}

int sftw_webp_encoder_add_rgba(
        SftwWebpEncoder* encoder,
        const uint8_t* rgba,
        int width,
        int height,
        int stride,
        int is_premultiplied,
        int timestamp_ms) {
    if (encoder == NULL || width != encoder->width || height != encoder->height) return 0;
    WebPPicture picture;
    if (!import_rgba(
            &picture,
            rgba,
            width,
            height,
            stride,
            is_premultiplied)) {
        return 0;
    }
    const int ok = WebPAnimEncoderAdd(
            encoder->encoder, &picture, timestamp_ms, &encoder->config);
    WebPPictureFree(&picture);
    return ok;
}

int sftw_webp_encoder_assemble(
        SftwWebpEncoder* encoder, int total_duration_ms, SftwWebpData* output) {
    if (output == NULL) return 0;
    output->bytes = NULL;
    output->size = 0;
    if (encoder == NULL || total_duration_ms <= 0) return 0;
    if (!WebPAnimEncoderAdd(encoder->encoder, NULL, total_duration_ms, NULL)) return 0;

    WebPData encoded;
    WebPDataInit(&encoded);
    if (!WebPAnimEncoderAssemble(encoder->encoder, &encoded)) {
        WebPDataClear(&encoded);
        return 0;
    }
    output->bytes = (uint8_t*) encoded.bytes;
    output->size = encoded.size;
    return 1;
}

void sftw_webp_encoder_delete(SftwWebpEncoder* encoder) {
    if (encoder == NULL) return;
    WebPAnimEncoderDelete(encoder->encoder);
    free(encoder);
}

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
        SftwWebpData* output) {
    if (output == NULL) return 0;
    output->bytes = NULL;
    output->size = 0;
    if (frame_duration_ms <= 0) return 0;

    WebPConfig config;
    WebPPicture picture;
    WebPMemoryWriter writer;
    WebPMux* mux = NULL;
    WebPData assembled;
    int picture_ready = 0;
    int writer_ready = 0;
    int ok = 0;

    WebPDataInit(&assembled);
    if (!configure(&config, quality, alpha_quality, method)) goto cleanup;
    if (!import_rgba(&picture, rgba, width, height, stride, is_premultiplied)) goto cleanup;
    picture_ready = 1;

    WebPMemoryWriterInit(&writer);
    writer_ready = 1;
    picture.writer = WebPMemoryWrite;
    picture.custom_ptr = &writer;
    if (!WebPEncode(&config, &picture) || writer.mem == NULL || writer.size == 0) goto cleanup;

    mux = WebPMuxNew();
    if (mux == NULL) goto cleanup;
    WebPMuxAnimParams animation = {0};
    animation.bgcolor = 0x00000000u;
    animation.loop_count = loop_count;
    if (WebPMuxSetAnimationParams(mux, &animation) != WEBP_MUX_OK) goto cleanup;
    if (WebPMuxSetCanvasSize(mux, width, height) != WEBP_MUX_OK) goto cleanup;

    WebPMuxFrameInfo frame;
    memset(&frame, 0, sizeof(frame));
    frame.bitstream.bytes = writer.mem;
    frame.bitstream.size = writer.size;
    frame.duration = frame_duration_ms;
    frame.id = WEBP_CHUNK_ANMF;
    frame.dispose_method = WEBP_MUX_DISPOSE_NONE;
    frame.blend_method = WEBP_MUX_NO_BLEND;

    // PushFrame is a mux-only operation: it copies the compressed payload and
    // deliberately performs no visual-equivalence or duplicate-frame pass.
    if (WebPMuxPushFrame(mux, &frame, 1) != WEBP_MUX_OK) goto cleanup;
    if (WebPMuxPushFrame(mux, &frame, 1) != WEBP_MUX_OK) goto cleanup;
    if (WebPMuxAssemble(mux, &assembled) != WEBP_MUX_OK) goto cleanup;

    output->bytes = (uint8_t*) assembled.bytes;
    output->size = assembled.size;
    assembled.bytes = NULL;
    assembled.size = 0;
    ok = 1;

cleanup:
    WebPDataClear(&assembled);
    if (mux != NULL) WebPMuxDelete(mux);
    if (writer_ready) WebPMemoryWriterClear(&writer);
    if (picture_ready) WebPPictureFree(&picture);
    return ok;
}

void sftw_webp_data_clear(SftwWebpData* data) {
    if (data == NULL) return;
    WebPFree(data->bytes);
    data->bytes = NULL;
    data->size = 0;
}
