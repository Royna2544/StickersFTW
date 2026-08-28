# StickersFTW conversion core

`conversion-core` is the Android-free part of StickersFTW's conversion
pipeline. It is a plain Kotlin/JVM library and runs on a desktop HotSpot JVM;
it does not link Android, AndroidX, or ART.

It currently owns the format and pack rules, size/duration budgets, media type
classification, frame-sampling policy, conversion results and settings, and
the WebM alpha-track demuxer. Android remains an adapter for the operations
that genuinely require `Bitmap`, Lottie rendering, or `MediaCodec`.

Build and test it independently:

```sh
./gradlew :conversion-core:build
```

The animated-WebP pixel encoder is a separate portable C target named
`stickerwebp_core`; see `app/src/main/cpp/README.md`.
