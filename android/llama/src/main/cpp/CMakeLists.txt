cmake_minimum_required(VERSION 3.22.1)

project("lomio_jni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pinned llama.cpp checkout (git submodule, see android/llama/llama.cpp).
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../llama.cpp)

# Reproducible builds (F-Droid): no absolute paths of this machine in the binaries. __FILE__ is
# used by ggml's asserts and log macros; map the checkout and the NDK to fixed prefixes.
get_filename_component(LOMIO_REPO_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../.. ABSOLUTE)
add_compile_options("-ffile-prefix-map=${LOMIO_REPO_ROOT}=." "-ffile-prefix-map=${ANDROID_NDK}=/ndk")

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
# libmtmd (vision via an mmproj GGUF) without the rest of tools/. Video needs ffmpeg in a
# subprocess, which Android doesn't allow (llama.cpp already turns LLAMA_SUBPROCESS off there).
set(LLAMA_BUILD_MTMD      ON  CACHE BOOL "" FORCE)
set(MTMD_VIDEO            OFF CACHE BOOL "" FORCE)

# Experimental Adreno GPU backend (OpenCL), arm64 only. -DLOMIO_OPENCL=OFF leaves it out.
option(LOMIO_OPENCL "Build the OpenCL (Adreno) GPU backend for arm64" ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # One CPU module per Android ARM feature level (armv8.0 / 8.2+dotprod / 8.2+fp16 /
    # 8.6+i8mm / 9.0+sve2 / 9.2+sme); ggml scores them and loads the best one.
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI     ON  CACHE BOOL "" FORCE)
    if(LOMIO_OPENCL)
        # The NDK has no OpenCL: compile against the Khronos headers (submodule) and link
        # against a stub libOpenCL.so made of the entry points ggml-opencl calls. The stub is
        # never packaged (see packaging.jniLibs.excludes); at runtime the vendor's own
        # /vendor/lib64/libOpenCL.so is used (AndroidManifest: uses-native-library).
        set(LOMIO_OPENCL_HEADERS ${CMAKE_CURRENT_LIST_DIR}/../../../opencl/OpenCL-Headers)
        file(GLOB LOMIO_OPENCL_SOURCES ${LLAMA_SRC}/ggml/src/ggml-opencl/*.cpp)
        set(LOMIO_CL_FUNCS "")
        foreach(src ${LOMIO_OPENCL_SOURCES})
            file(READ ${src} content)
            string(REGEX MATCHALL "cl[A-Z][A-Za-z0-9_]+[ \t]*\\(" calls "${content}")
            list(APPEND LOMIO_CL_FUNCS ${calls})
        endforeach()
        list(TRANSFORM LOMIO_CL_FUNCS REPLACE "[ \t]*\\($" "")
        list(REMOVE_DUPLICATES LOMIO_CL_FUNCS)
        set(stub_src "/* Generated: link-time stub for libOpenCL.so, never shipped. */\n")
        foreach(fn ${LOMIO_CL_FUNCS})
            string(APPEND stub_src "void ${fn}(void) {}\n")
        endforeach()
        file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/opencl_stub.c "${stub_src}")
        add_library(OpenCL SHARED ${CMAKE_CURRENT_BINARY_DIR}/opencl_stub.c)
        set(OpenCL_INCLUDE_DIR ${LOMIO_OPENCL_HEADERS} CACHE PATH "" FORCE)
        set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
        set(GGML_OPENCL                    ON  CACHE BOOL "" FORCE)
        set(GGML_OPENCL_EMBED_KERNELS      ON  CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON  CACHE BOOL "" FORCE)
    endif()
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

if(TARGET ggml-opencl)
    # Renamed so ggml_backend_load_all_from_path() (which looks for libggml-<name>*.so) skips
    # it: the GPU backend is only loaded when the user turns GPU acceleration on (lomio_jni.cpp).
    set_target_properties(ggml-opencl PROPERTIES OUTPUT_NAME lomio-opencl)
    target_link_options(ggml-opencl PRIVATE "-Wl,-z,max-page-size=16384")
endif()

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
add_library(lomio_jni SHARED lomio_jni.cpp)

target_include_directories(lomio_jni PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/ggml/include)

if(TARGET ggml-opencl)
    target_compile_definitions(lomio_jni PRIVATE LOMIO_HAS_OPENCL=1)
endif()

target_link_libraries(lomio_jni
        llama
        llama-common
        mtmd
        ggml
        android
        log)

# 16 KB page alignment (required for Android 15+ targets on Play). NDK r28+ defaults to it;
# keep it explicit for every library we ship.
target_link_options(lomio_jni PRIVATE "-Wl,-z,max-page-size=16384")
