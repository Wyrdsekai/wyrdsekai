cmake_minimum_required(VERSION 3.22)
project(wyrd-llama)

# llama.cpp source — shared with daemon via symlink or submodule
# Usage: git submodule add https://github.com/ggml-org/llama.cpp.git
#        clients/kmp/llama.cpp
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp"
    CACHE PATH "Path to llama.cpp source")

# llama.cpp build options for Android
set(GGML_NATIVE OFF)          # Can't use native detection when cross-compiling
set(GGML_VULKAN OFF)          # Disable Vulkan by default (unreliable on Android)
set(GGML_CPU_AARCH64 ON)      # ARM NEON + SVE optimization
set(BUILD_SHARED_LIBS ON)

# ARM optimization flags for modern SoCs (SD 8 Elite, SD 8 Gen 3)
# armv8.7-a enables: NEON, dotprod, i8mm, bf16, SVE — critical for inference speed.
# ONLY valid for arm64-v8a — clang rejects `-march=armv8.7-a` for x86_64 (used by
# the emulator: "unknown target CPU 'armv8.7-a'"), so guard it by ABI.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.7-a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.7-a")
endif()

if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)
else()
    message(WARNING "llama.cpp not found at ${LLAMA_CPP_DIR}. "
        "Clone it: git clone https://github.com/ggml-org/llama.cpp.git clients/kmp/llama.cpp")
    return()
endif()

# JNI bridge — distinct library name from daemon's libllama-jni.so
add_library(wyrd-llama SHARED wyrd_llama_jni.cpp)

target_include_directories(wyrd-llama PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_link_libraries(wyrd-llama
    llama
    ggml
    log   # Android logging
)

# Strip debug info for smaller APK
set_target_properties(wyrd-llama PROPERTIES
    LINK_FLAGS_RELEASE "-s"
)
