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

            if(EXISTS "${_VULKAN_HDR_DIR}/vulkan/vulkan.hpp" AND
               EXISTS "${_VULKAN_HDR_DIR}/spirv/unified1/spirv.hpp")

                # Pre-set Vulkan CMake variables so ggml-vulkan's find_package(Vulkan REQUIRED)
                # succeeds without searching the cross-compilation sysroot.
                set(Vulkan_INCLUDE_DIR      "${_VULKAN_HDR_DIR}" CACHE PATH     "" FORCE)
                set(Vulkan_GLSLC_EXECUTABLE "${_NDK_GLSLC}"      CACHE FILEPATH "" FORCE)

                # libvulkan stub: Android CMake toolchain resolves the ABI triple
                find_library(_VULKAN_SYSTEM_LIB vulkan)
                if(_VULKAN_SYSTEM_LIB)
                    set(Vulkan_LIBRARY "${_VULKAN_SYSTEM_LIB}" CACHE FILEPATH "" FORCE)
                    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
                    # Remember the header dir — used after add_subdirectory() below
                    # to directly inject it into ggml-vulkan's include paths, bypassing
                    # any FindVulkan / Vulkan::Vulkan propagation issue in cross-builds.
                    set(_VULKAN_EXTRA_INC "${_VULKAN_HDR_DIR}")
                    message(STATUS "Vulkan: ON  inc=${_VULKAN_HDR_DIR}  lib=${_VULKAN_SYSTEM_LIB}")
                else()
                    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
                    message(STATUS "Vulkan: OFF (libvulkan stub not found by find_library)")
                endif()
            else()
                set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
                message(STATUS "Vulkan: OFF (C++ headers missing — run: apt-get install libvulkan-dev spirv-headers)")
            endif()
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

    # Direct include-path injection for ggml-vulkan.
    # FindVulkan's Vulkan::Vulkan INTERFACE_INCLUDE_DIRECTORIES doesn't always
    # propagate correctly in Android cross-builds.  Explicitly adding the host
    # header dir to the target guarantees clang++ receives the -I flag.
    if(GGML_VULKAN AND _VULKAN_EXTRA_INC AND TARGET ggml-vulkan)
        target_include_directories(ggml-vulkan PRIVATE "${_VULKAN_EXTRA_INC}")
        message(STATUS "Vulkan: injected -I${_VULKAN_EXTRA_INC} into ggml-vulkan")
    endif()

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
