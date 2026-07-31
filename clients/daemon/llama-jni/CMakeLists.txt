cmake_minimum_required(VERSION 3.22)
project(llama-jni)

# llama.cpp source (git submodule or extracted)
# Usage: cd llama-jni && git submodule add https://github.com/ggml-org/llama.cpp.git
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# llama.cpp build options for Android
set(GGML_NATIVE OFF)          # No host-specific optimization
set(GGML_VULKAN OFF)          # Disable Vulkan by default (unreliable on Android)
set(GGML_CPU_AARCH64 ON)      # ARM NEON + SVE optimization
set(BUILD_SHARED_LIBS ON)

if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)
else()
    message(WARNING "llama.cpp not found at ${LLAMA_CPP_DIR}. "
        "Run: git submodule add https://github.com/ggml-org/llama.cpp.git llama-jni/llama.cpp")
    return()
endif()

# JNI bridge
add_library(llama-jni SHARED llama_jni.cpp)

target_include_directories(llama-jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_link_libraries(llama-jni
    llama
    ggml
    log   # Android logging
)

# Strip debug info for smaller APK
set_target_properties(llama-jni PROPERTIES
    LINK_FLAGS_RELEASE "-s"
)
