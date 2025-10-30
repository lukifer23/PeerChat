cmake_minimum_required(VERSION 3.22)
project(peerchat_engine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Configure ggml/llama for Vulkan on Android
set(GGML_USE_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_CHECK_RESULTS ON CACHE BOOL "" FORCE)
set(GGML_USE_K_QUANTS ON CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_ZDNN OFF CACHE BOOL "" FORCE)
set(GGML_MUSA OFF CACHE BOOL "" FORCE)
set(GGML_CANN OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama)

add_library(engine SHARED
        peer_engine_jni.cpp
)

target_include_directories(engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/llama
)

find_library(log-lib log)
target_link_libraries(engine
        ${log-lib}
        llama
)

# Harden compile and link flags for Android arm64
add_compile_options(
        -O3
        -DNDEBUG
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
        -march=armv8-a
        -fno-plt
)
add_link_options(
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -flto
)

