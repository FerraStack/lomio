cmake_minimum_required(VERSION 3.22.1)

project("lomio_jni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pinned llama.cpp checkout (git submodule, see android/llama/llama.cpp).
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../llama.cpp)

# ---------------------------------------------------------------------------
# llama.cpp build configuration
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
# CPU backends are separate loadable modules (libggml-cpu-*.so) picked at runtime by
# ggml_backend_load_all_from_path(nativeLibraryDir).
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # One CPU module per Android ARM feature level (armv8.0 / 8.2+dotprod / 8.2+fp16 /
    # 8.6+i8mm / 9.0+sve2 / 9.2+sme); ggml scores them and loads the best one.
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI     ON  CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # Emulator only: a single baseline module (x86-64 Android ABI guarantees SSE4.2).
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
    set(GGML_SSE42            ON  CACHE BOOL "" FORCE)
    set(GGML_AVX              OFF CACHE BOOL "" FORCE)
    set(GGML_AVX2             OFF CACHE BOOL "" FORCE)
    set(GGML_FMA              OFF CACHE BOOL "" FORCE)
    set(GGML_F16C             OFF CACHE BOOL "" FORCE)
    set(GGML_BMI2             OFF CACHE BOOL "" FORCE)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
add_library(lomio_jni SHARED lomio_jni.cpp)

target_include_directories(lomio_jni PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(lomio_jni
        llama
        llama-common
        ggml
        android
        log)

# 16 KB page alignment (required for Android 15+ targets on Play). NDK r28+ defaults to it;
# keep it explicit for every library we ship.
target_link_options(lomio_jni PRIVATE "-Wl,-z,max-page-size=16384")
