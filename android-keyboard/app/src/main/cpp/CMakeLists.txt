cmake_minimum_required(VERSION 3.22.1)
project(offlinevoice C CXX)

if(NOT WHISPER_SRC_DIR OR NOT EXISTS "${WHISPER_SRC_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp source not found at '${WHISPER_SRC_DIR}'. Run the Gradle build, which downloads it.")
endif()

# Build ggml as shared libraries and compile one CPU backend per ARM feature
# level (plain ARMv8, dot-product, FP16, int8 matmul, SVE...). At runtime ggml
# scores each one against the phone's CPU and loads the fastest that works,
# so newer phones run fast and older phones still run.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SRC_DIR} whisper)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper ggml android log)
