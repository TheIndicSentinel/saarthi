cmake_minimum_required(VERSION 3.22)
project(llama_bridge)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    # ── Full build with llama.cpp submodule ──────────────────────────────────
    message(STATUS "llama.cpp submodule found — building full native engine")

    # Disable everything we don't need for mobile
    set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
    set(LLAMA_NATIVE          OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)

    # Vulkan GPU acceleration — Android NDK 25+ ships glslc in shader-tools/,
    # so no external LunarG SDK is needed.  We detect the NDK-bundled glslc and
    # pre-set the Vulkan CMake variables before ggml-vulkan's find_package() runs.
    if(ANDROID)
        if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
            set(_NDK_HOST_TAG "linux-x86_64")
        elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
            if(CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "arm64")
                set(_NDK_HOST_TAG "darwin-arm64")
            else()
                set(_NDK_HOST_TAG "darwin-x86_64")
            endif()
        else()
            set(_NDK_HOST_TAG "windows-x86_64")
        endif()

        set(_NDK_SYSROOT "${ANDROID_NDK}/toolchains/llvm/prebuilt/${_NDK_HOST_TAG}/sysroot")
        set(_NDK_GLSLC   "${ANDROID_NDK}/shader-tools/${_NDK_HOST_TAG}/glslc")

        if(EXISTS "${_NDK_GLSLC}")
            set(Vulkan_INCLUDE_DIR      "${_NDK_SYSROOT}/usr/include"
                CACHE PATH     "" FORCE)
            set(Vulkan_LIBRARY          "${_NDK_SYSROOT}/usr/lib/${ANDROID_ABI}/libvulkan.so"
                CACHE FILEPATH "" FORCE)
            set(Vulkan_GLSLC_EXECUTABLE "${_NDK_GLSLC}"
                CACHE FILEPATH "" FORCE)
            set(GGML_VULKAN ON CACHE BOOL "" FORCE)
            message(STATUS "Vulkan: ON  (NDK glslc ${_NDK_GLSLC})")
        else()
            set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
            message(STATUS "Vulkan: OFF (NDK glslc not found at ${_NDK_GLSLC})")
        endif()
    else()
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()

    set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)

    # Android NEON (ARM vector instructions — free perf on all modern Android)
    set(GGML_NEON             ON  CACHE BOOL "" FORCE)

    # Use Android's bundled OpenBLAS-equivalent via GGML_BLAS is not needed;
    # NEON-optimised kernels are already fast on Snapdragon.

    add_subdirectory(${LLAMA_DIR} llama_cpp_build)

    add_library(llama_bridge SHARED llama_bridge.cpp)

    target_include_directories(llama_bridge PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include
    )

    target_link_libraries(llama_bridge
        llama
        ggml
        android
        log
    )

    target_compile_options(llama_bridge PRIVATE
        -O3
        -ffast-math
        -fno-finite-math-only   # keep inf/nan handling for safety
    )

else()
    # ── Stub build — submodule absent, app still compiles ───────────────────
    message(STATUS "llama.cpp submodule not found — building stub library")

    add_library(llama_bridge SHARED llama_bridge_stub.cpp)
    target_link_libraries(llama_bridge android log)
endif()
