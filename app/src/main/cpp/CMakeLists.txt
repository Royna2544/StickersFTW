cmake_minimum_required(VERSION 3.22.1)
project(stickerwebp C)

# libwebp 1.1.2's Android artifact shipped its .so files linked at a 4KB page
# size, which will not load on a 16KB-page device and is fixed only by
# relinking. Building it here means NDK r28+ does that for us (16KB is its
# default), and we get to link everything into one library instead of the
# five that AAR shipped.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/libwebp/CMakeLists.txt")
    message(FATAL_ERROR
        "libwebp source missing. It is a git submodule:\n"
        "    git submodule update --init --recursive")
endif()

# Everything below is a command line tool, a viewer, or a test harness. The
# only piece this app needs is the animation encoder in libwebpmux.
set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)
set(WEBP_LINK_STATIC ON CACHE BOOL "" FORCE)

add_subdirectory(libwebp EXCLUDE_FROM_ALL)

add_library(stickerwebp_core STATIC stickerwebp_core.c)
set_target_properties(stickerwebp_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(stickerwebp_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")

# The mux target is named libwebpmux upstream; its output file is webpmux.
target_link_libraries(stickerwebp_core PUBLIC libwebpmux webp sharpyuv)

if(ANDROID)
    # Only this thin adapter knows about JNI and android.graphics.Bitmap. The
    # conversion implementation above is also built and tested on a host with
    # no JVM or Android Runtime.
    add_library(stickerwebp SHARED webp_anim_encoder.c)
    target_link_libraries(stickerwebp PRIVATE stickerwebp_core jnigraphics log)

    # Belt and braces: NDK r28+ already defaults to this, but an older NDK on a
    # contributor's machine would silently reintroduce the exact bug this whole
    # module exists to fix.
    target_link_options(stickerwebp PRIVATE "-Wl,-z,max-page-size=16384")
endif()

option(STICKERSFTW_BUILD_NATIVE_TESTS "Build host tests for the ART-free converter" OFF)
if(STICKERSFTW_BUILD_NATIVE_TESTS)
    enable_testing()
    add_executable(stickerwebp_core_test tests/stickerwebp_core_test.c)
    target_link_libraries(stickerwebp_core_test PRIVATE stickerwebp_core webpdemux)
    add_test(NAME stickerwebp_core_test COMMAND stickerwebp_core_test)
endif()
