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

    # Phase 1: CPU-only (no glslc shader compiler needed on host)
    # Phase 2: set GGML_VULKAN=ON after installing LunarG Vulkan SDK on Mac
    set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
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
