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
            # ggml-vulkan.cpp includes vulkan/vulkan.hpp and spirv/unified1/spirv.hpp
            # (C++ Vulkan bindings).  The NDK sysroot only ships the C API (vulkan.h).
            # These C++ headers live on the HOST build machine.
            #
            # Explicit -I paths passed by CMake are absolute, NOT sysroot-relative,
            # so the cross-compiler finds host headers at their real paths even when
            # building with --sysroot.
            #
            # CI requirement: apt-get install libvulkan-dev spirv-headers
            #   → puts vulkan/vulkan.hpp and spirv/unified1/spirv.hpp under /usr/include
            if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
                set(_VULKAN_HDR_DIR "/usr/include")
            else()
                # macOS: fall back to NDK C headers — Vulkan will be disabled below
                # if the C++ headers aren't present.
                set(_VULKAN_HDR_DIR "${_NDK_SYSROOT}/usr/include")
            endif()

            # The CI workflow copies vulkan/*.hpp and spirv/ into the NDK sysroot
            # before CMake runs, so clang++'s --sysroot already covers them.
            # We only need to confirm the headers are present before enabling Vulkan.
            set(_NDK_INC "${_NDK_SYSROOT}/usr/include")
            if(EXISTS "${_NDK_INC}/vulkan/vulkan.hpp" AND
               EXISTS "${_NDK_INC}/spirv/unified1/spirv.hpp")

                set(Vulkan_INCLUDE_DIR      "${_NDK_INC}" CACHE PATH     "" FORCE)
                set(Vulkan_GLSLC_EXECUTABLE "${_NDK_GLSLC}"    CACHE FILEPATH "" FORCE)

                find_library(_VULKAN_SYSTEM_LIB vulkan)
                if(_VULKAN_SYSTEM_LIB)
                    set(Vulkan_LIBRARY "${_VULKAN_SYSTEM_LIB}" CACHE FILEPATH "" FORCE)
                    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
                    message(STATUS "Vulkan: ON  inc=${_NDK_INC}  lib=${_VULKAN_SYSTEM_LIB}")
                else()
                    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
                    message(STATUS "Vulkan: OFF (libvulkan stub not found)")
                endif()
            else()
                set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
                message(STATUS "Vulkan: OFF (C++ headers not in NDK sysroot — CI step may have failed)")
            endif()
        else()
            set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
            message(STATUS "Vulkan: OFF (NDK glslc not found at ${_NDK_GLSLC})")
        endif()
    else()
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()

    set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)

    # NEON is safe — it has fixed 128-bit width on all ARMv8/ARMv9 cores.
    set(GGML_NEON             ON  CACHE BOOL "" FORCE)

    # SVE2 (Scalable Vector Extension 2) is DISABLED on Android.
    # Snapdragon 8 Gen 2 has heterogeneous SVE2 core widths:
    #   Cortex-X3 prime core  → 256-bit SVE2
    #   Cortex-A715/A510      → 128-bit SVE2
    # When Android's scheduler migrates a GGML worker thread mid-computation
    # from a 256-bit core to a 128-bit core, the vectorized kernel writes beyond
    # its buffer → SIGSEGV.  Android 16 (API 36) catches this via MTE (Memory
    # Tagging Extension) where older Android versions silently corrupted memory.
    # Disabling SVE2 forces GGML to use the fixed-width NEON path on all cores.
    # SVE/SVE2/SME variants are patched out of ggml/src/CMakeLists.txt below
    # before add_subdirectory so the change works with any upstream checkout
    # without requiring a fork or a committed submodule change.

    if(ANDROID)
        set(_GGML_CPU_CMAKE "${LLAMA_DIR}/ggml/src/CMakeLists.txt")
        file(READ "${_GGML_CPU_CMAKE}" _ggml_cpu)
        string(REPLACE
            "ggml_add_cpu_backend_variant(android_armv9.0_1    DOTPROD MATMUL_INT8 FP16_VECTOR_ARITHMETIC SVE2)"
            "# Removed android_armv9.0_1 — SVE2 SIGSEGV on Snapdragon 8 Gen 2 (heterogeneous core widths)"
            _ggml_cpu "${_ggml_cpu}")
        string(REPLACE
            "ggml_add_cpu_backend_variant(android_armv9.2_1    DOTPROD MATMUL_INT8 FP16_VECTOR_ARITHMETIC SVE SME)"
            "# Removed android_armv9.2_1 — SVE+SME SIGSEGV on Snapdragon 8 Gen 2"
            _ggml_cpu "${_ggml_cpu}")
        string(REPLACE
            "ggml_add_cpu_backend_variant(android_armv9.2_2    DOTPROD MATMUL_INT8 FP16_VECTOR_ARITHMETIC SVE SVE2 SME)"
            "# Removed android_armv9.2_2 — SVE+SVE2+SME SIGSEGV on Snapdragon 8 Gen 2"
            _ggml_cpu "${_ggml_cpu}")
        file(WRITE "${_GGML_CPU_CMAKE}" "${_ggml_cpu}")
        message(STATUS "SVE patch applied: ARMv9 SVE/SVE2/SME Android variants removed from ggml/src/CMakeLists.txt")
    endif()

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
