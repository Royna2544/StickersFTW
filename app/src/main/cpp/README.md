# ART-free WebP conversion core

`stickerwebp_core` is a platform-neutral C library over the repository's
pinned libwebp submodule. Its public API is `stickerwebp_core.h`: callers pass
raw RGBA8888 buffers with explicit dimensions, stride, and premultiplication,
and receive explicitly owned WebP bytes. It has no JNI or Android headers.

`webp_anim_encoder.c` is the thin Android adapter. It only locks a `Bitmap`,
calls the core, and returns the resulting bytes to Kotlin. The 16 KB ELF page
alignment rule applies to that Android shared-library wrapper, not to the core
API.

The host regression test verifies that a still image can be stored as two
pixel-identical `ANMF` frames without `WebPAnimEncoder` coalescing them:

```sh
git submodule update --init app/src/main/cpp/libwebp
cmake -S app/src/main/cpp -B build/stickerwebp-host \
  -DSTICKERSFTW_BUILD_NATIVE_TESTS=ON
cmake --build build/stickerwebp-host
ctest --test-dir build/stickerwebp-host --output-on-failure
```

When this directory is configured by the Android Gradle plugin, CMake also
builds `stickerwebp`, the JNI shared library consumed by the app.
