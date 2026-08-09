cmake_minimum_required(VERSION 3.22.1)

project(il2cppmanager LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_ARM64_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_X86_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTEST OFF CACHE BOOL "" FORCE)
set(CAPSTONE_INSTALL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    capstone
    URL https://github.com/capstone-engine/capstone/archive/refs/tags/5.0.9.tar.gz
    URL_HASH SHA256=0619DA31AF08152600AF95C481527EF6D756C0A8404FCA7544A4FDF6DFC2C0F9
)
FetchContent_MakeAvailable(capstone)
set_target_properties(capstone capstone_static PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(
    il2cpp_metadata
    STATIC
    metadata_parser.cpp
    metadata_search.cpp
)

target_compile_features(il2cpp_metadata PUBLIC cxx_std_17)
target_include_directories(il2cpp_metadata PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(il2cpp_metadata PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(
    il2cppmanager
    SHARED
    direct_call_analyzer.cpp
    jni_bridge.cpp
    process_memory.cpp
    runtime_metadata.cpp
)

target_compile_features(il2cppmanager PRIVATE cxx_std_17)
target_compile_options(
    il2cppmanager
    PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
target_link_libraries(il2cppmanager PRIVATE capstone_static il2cpp_metadata log)
