cmake_minimum_required(VERSION 3.22.1)
project(rawdecoder CXX)

# LibRaw (LGPL 2.1 / CDDL) scaricata in fase di configurazione.
include(FetchContent)
FetchContent_Declare(
    libraw
    URL https://github.com/LibRaw/LibRaw/archive/refs/tags/0.21.3.tar.gz
)
FetchContent_GetProperties(libraw)
if(NOT libraw_POPULATED)
    FetchContent_Populate(libraw)
endif()
set(LR ${libraw_SOURCE_DIR})

include(${CMAKE_CURRENT_SOURCE_DIR}/libraw_sources.cmake)

set(OPT_FLAGS -O3 -fexceptions -frtti -w -fopenmp)

add_library(libraw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw_static PUBLIC ${LR})
target_compile_options(libraw_static PRIVATE ${OPT_FLAGS})

add_library(rawdecoder SHARED rawdecoder.cpp)
target_compile_options(rawdecoder PRIVATE -O2 -fexceptions -frtti -fopenmp)
target_link_libraries(rawdecoder libraw_static jnigraphics log -fopenmp -static-openmp)
