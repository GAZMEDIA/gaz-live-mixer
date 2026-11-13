cmake_minimum_required(VERSION 3.22.1)
project(gazllm)

set(CMAKE_CXX_STANDARD 17)
# llama.cpp is fetched by the build (see .github/workflows/build.yml) into ./llama.cpp
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(llama.cpp)

add_library(gazllm SHARED gazllm.cpp)
target_link_libraries(gazllm llama ggml android log)
