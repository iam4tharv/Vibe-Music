# Native analysis front end for Automix.
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2 adds the log-mel front end the
# Beat This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3 adds the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree rather than here, so a
# later phase adding a Rubber Band-based transition renderer can add sources
# to that same tree without duplicating this module.

cmake_minimum_required(VERSION 3.22.1)
project(vibemusic_analysis LANGUAGES C CXX)

get_filename_component(VIBEMUSIC_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(vibemusic_analysis SHARED
    ${VIBEMUSIC_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${VIBEMUSIC_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${VIBEMUSIC_NATIVE_DIR}/analyzer/resampler.cpp
    ${VIBEMUSIC_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${VIBEMUSIC_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(vibemusic_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${VIBEMUSIC_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
target_compile_options(vibemusic_analysis PRIVATE -O3 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(vibemusic_analysis ${log-lib})

# 16KB page-size support (Android 15+ 16KB kernels, Play requirement): align
# LOAD segments to 16KB. A superset of 4KB alignment, so 4KB devices are
# unaffected. APK-side alignment of uncompressed .so files is handled by AGP.
target_link_options(vibemusic_analysis PRIVATE "-Wl,-z,max-page-size=16384")

# DSD playback (DSF/DFF). The decimator turns 1-bit DSD into float PCM for
# DsdExtractor; DST is the lossless compression SACD rips use inside DFF.
# DST is LGPL (see dsd/dst/NOTICE.md), so it is a library of its own that can
# be rebuilt or swapped without touching the rest.
add_library(vibemusic_dsd SHARED dsd/dsd_decimator.cpp)
target_compile_options(vibemusic_dsd PRIVATE -O3 -fno-fast-math -ffp-contract=off -fvisibility=hidden)
target_link_options(vibemusic_dsd PRIVATE "-Wl,-z,max-page-size=16384")

add_library(vibemusic_dst SHARED dsd/dst/decoder.c dsd/dst/jni.cpp)
target_compile_options(vibemusic_dst PRIVATE -O3 -fno-strict-aliasing -fvisibility=hidden)
target_link_options(vibemusic_dst PRIVATE "-Wl,-z,max-page-size=16384")

# Native AAudio Direct Audio Engine (MMAP / Exclusive Mixer Bypass)
find_library(aaudio-lib aaudio)

add_library(vibe_aaudio SHARED
    native-audio.cpp
    audio/aaudio_engine.cpp
    jni/aaudio_jni.cpp
)
target_include_directories(vibe_aaudio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
target_compile_options(vibe_aaudio PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(vibe_aaudio ${aaudio-lib} ${log-lib})
target_link_options(vibe_aaudio PRIVATE "-Wl,-z,max-page-size=16384")

