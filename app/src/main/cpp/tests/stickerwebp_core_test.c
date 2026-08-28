#include "stickerwebp_core.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "webp/demux.h"

enum {
    WIDTH = 32,
    HEIGHT = 24,
    FRAME_DURATION_MS = 100,
    WHATSAPP_ANIMATED_MAX_BYTES = 500000,
};

#define CHECK(condition)                                                        \
    do {                                                                        \
        if (!(condition)) {                                                     \
            fprintf(stderr, "CHECK failed at %s:%d: %s\n",                    \
                    __FILE__, __LINE__, #condition);                            \
            abort();                                                            \
        }                                                                       \
    } while (0)

static void repeated_frame_is_a_pixel_identical_animation(void) {
    uint8_t rgba[WIDTH * HEIGHT * 4];
    for (int y = 0; y < HEIGHT; ++y) {
        for (int x = 0; x < WIDTH; ++x) {
            uint8_t* pixel = rgba + (y * WIDTH + x) * 4;
            pixel[0] = (uint8_t) (x * 7);
            pixel[1] = (uint8_t) (y * 9);
            pixel[2] = 180;
            pixel[3] = x < WIDTH / 3 ? 0 : 255;
        }
    }

    SftwWebpData encoded = {0};
    CHECK(sftw_webp_encode_repeated_rgba(
            rgba,
            WIDTH,
            HEIGHT,
            WIDTH * 4,
            0,
            FRAME_DURATION_MS,
            0,
            80.0f,
            100,
            4,
            &encoded));
    CHECK(encoded.bytes != NULL);
    CHECK(encoded.size > 0 && encoded.size <= WHATSAPP_ANIMATED_MAX_BYTES);

    WebPData webp = {encoded.bytes, encoded.size};
    WebPDemuxer* demux = WebPDemux(&webp);
    CHECK(demux != NULL);
    CHECK(WebPDemuxGetI(demux, WEBP_FF_FORMAT_FLAGS) & ANIMATION_FLAG);
    CHECK(WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT) == 2);
    CHECK(WebPDemuxGetI(demux, WEBP_FF_LOOP_COUNT) == 0);
    CHECK(WebPDemuxGetI(demux, WEBP_FF_BACKGROUND_COLOR) == 0);

    WebPIterator first;
    WebPIterator second;
    CHECK(WebPDemuxGetFrame(demux, 1, &first));
    CHECK(WebPDemuxGetFrame(demux, 2, &second));
    CHECK(first.duration == FRAME_DURATION_MS);
    CHECK(second.duration == FRAME_DURATION_MS);
    CHECK(first.fragment.size == second.fragment.size);
    CHECK(memcmp(first.fragment.bytes, second.fragment.bytes, first.fragment.size) == 0);
    WebPDemuxReleaseIterator(&second);
    WebPDemuxReleaseIterator(&first);

    WebPAnimDecoderOptions options;
    CHECK(WebPAnimDecoderOptionsInit(&options));
    options.color_mode = MODE_RGBA;
    WebPAnimDecoder* decoder = WebPAnimDecoderNew(&webp, &options);
    CHECK(decoder != NULL);
    WebPAnimInfo info;
    CHECK(WebPAnimDecoderGetInfo(decoder, &info));
    CHECK(info.frame_count == 2 && info.canvas_width == WIDTH && info.canvas_height == HEIGHT);
    const size_t canvas_bytes = (size_t) WIDTH * HEIGHT * 4;
    uint8_t* first_pixels = (uint8_t*) malloc(canvas_bytes);
    CHECK(first_pixels != NULL);
    uint8_t* decoded = NULL;
    int timestamp_ms = 0;
    CHECK(WebPAnimDecoderGetNext(decoder, &decoded, &timestamp_ms));
    CHECK(timestamp_ms == FRAME_DURATION_MS);
    CHECK(decoded[3] == 0);
    CHECK(decoded[((size_t) WIDTH - 1) * 4 + 3] == 255);
    memcpy(first_pixels, decoded, canvas_bytes);
    CHECK(WebPAnimDecoderGetNext(decoder, &decoded, &timestamp_ms));
    CHECK(timestamp_ms == FRAME_DURATION_MS * 2);
    CHECK(memcmp(first_pixels, decoded, canvas_bytes) == 0);
    CHECK(!WebPAnimDecoderHasMoreFrames(decoder));
    free(first_pixels);
    WebPAnimDecoderDelete(decoder);
    WebPDemuxDelete(demux);
    sftw_webp_data_clear(&encoded);
}

static void regular_encoder_still_emits_multiple_frames(void) {
    uint8_t first[4 * 4 * 4];
    uint8_t second[4 * 4 * 4];
    memset(first, 0, sizeof(first));
    memset(second, 0, sizeof(second));
    for (size_t i = 0; i < sizeof(first); i += 4) {
        first[i] = 255;
        first[i + 3] = 255;
        second[i + 1] = 255;
        second[i + 3] = 255;
    }

    SftwWebpEncoder* encoder = sftw_webp_encoder_create(4, 4, 0, 1);
    CHECK(encoder != NULL);
    CHECK(sftw_webp_encoder_configure(encoder, 80.0f, 100, 4));
    CHECK(sftw_webp_encoder_add_rgba(encoder, first, 4, 4, 4 * 4, 0, 0));
    CHECK(sftw_webp_encoder_add_rgba(encoder, second, 4, 4, 4 * 4, 0, 100));
    CHECK(!sftw_webp_encoder_add_rgba(encoder, second, 2, 4, 4 * 4, 0, 150));

    SftwWebpData encoded = {0};
    CHECK(sftw_webp_encoder_assemble(encoder, 200, &encoded));
    WebPData webp = {encoded.bytes, encoded.size};
    WebPDemuxer* demux = WebPDemux(&webp);
    CHECK(demux != NULL);
    CHECK(WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT) == 2);
    CHECK(WebPDemuxGetI(demux, WEBP_FF_BACKGROUND_COLOR) == 0);
    WebPDemuxDelete(demux);
    sftw_webp_data_clear(&encoded);
    sftw_webp_encoder_delete(encoder);
}

static void rejects_dimensions_beyond_webp_limit(void) {
    uint8_t pixel[4] = {0, 0, 0, 0};
    SftwWebpData encoded = {0};
    CHECK(sftw_webp_encoder_create(16384, 1, 0, 1) == NULL);
    CHECK(!sftw_webp_encode_repeated_rgba(
            pixel, 16384, 1, 16384 * 4, 0, 100, 0, 80.0f, 100, 4, &encoded));
    CHECK(encoded.bytes == NULL && encoded.size == 0);
}

static void imports_premultiplied_pixels_with_padded_stride(void) {
    enum { PADDED_WIDTH = 3, PADDED_HEIGHT = 2, PADDED_STRIDE = 16 };
    uint8_t rgba[PADDED_HEIGHT * PADDED_STRIDE];
    memset(rgba, 0, sizeof(rgba));
    for (int y = 0; y < PADDED_HEIGHT; ++y) {
        for (int x = 0; x < PADDED_WIDTH; ++x) {
            uint8_t* pixel = rgba + y * PADDED_STRIDE + x * 4;
            // Premultiplied half-alpha red: the straight value is 255 red.
            pixel[0] = 128;
            pixel[3] = 128;
        }
        // Bright green padding makes a tight-stride bug visually obvious.
        uint8_t* padding = rgba + y * PADDED_STRIDE + PADDED_WIDTH * 4;
        padding[1] = 255;
        padding[3] = 255;
    }

    SftwWebpData encoded = {0};
    CHECK(sftw_webp_encode_repeated_rgba(
            rgba,
            PADDED_WIDTH,
            PADDED_HEIGHT,
            PADDED_STRIDE,
            1,
            FRAME_DURATION_MS,
            0,
            100.0f,
            100,
            6,
            &encoded));

    const WebPData webp = {encoded.bytes, encoded.size};
    WebPAnimDecoderOptions options;
    CHECK(WebPAnimDecoderOptionsInit(&options));
    options.color_mode = MODE_RGBA;
    WebPAnimDecoder* decoder = WebPAnimDecoderNew(&webp, &options);
    CHECK(decoder != NULL);
    uint8_t* decoded = NULL;
    int timestamp_ms = 0;
    CHECK(WebPAnimDecoderGetNext(decoder, &decoded, &timestamp_ms));
    for (int y = 0; y < PADDED_HEIGHT; ++y) {
        for (int x = 0; x < PADDED_WIDTH; ++x) {
            const uint8_t* pixel = decoded + (y * PADDED_WIDTH + x) * 4;
            CHECK(pixel[0] >= 245);
            CHECK(pixel[1] <= 10);
            CHECK(pixel[2] <= 10);
            CHECK(pixel[3] == 128);
        }
    }
    WebPAnimDecoderDelete(decoder);
    sftw_webp_data_clear(&encoded);
}

int main(void) {
    repeated_frame_is_a_pixel_identical_animation();
    regular_encoder_still_emits_multiple_frames();
    rejects_dimensions_beyond_webp_limit();
    imports_premultiplied_pixels_with_padded_stride();
    return EXIT_SUCCESS;
}
