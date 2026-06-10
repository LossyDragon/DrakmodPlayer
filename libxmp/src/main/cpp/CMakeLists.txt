cmake_minimum_required(VERSION 3.4)

set(PROJECT_NAME "xmp-jni")

project(${PROJECT_NAME} LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add libxmp's CMakeLists.txt
add_subdirectory(libxmp)
target_compile_options(xmp_static PRIVATE -Wno-unused-function)

file(GLOB OPENMPT_COMMON CONFIGURE_DEPENDS openmpt/common/*.cpp)
file(GLOB OPENMPT_SOUNDLIB CONFIGURE_DEPENDS openmpt/soundlib/*.cpp)
file(GLOB OPENMPT_PLUGINS CONFIGURE_DEPENDS openmpt/soundlib/plugins/*.cpp)
file(GLOB OPENMPT_DMO CONFIGURE_DEPENDS openmpt/soundlib/plugins/dmo/*.cpp)
file(GLOB OPENMPT_SOUNDDSP CONFIGURE_DEPENDS openmpt/sounddsp/*.cpp)

add_library(openmpt_static STATIC
    ${OPENMPT_COMMON}
    openmpt/libopenmpt/libopenmpt_c.cpp
    openmpt/libopenmpt/libopenmpt_cxx.cpp
    openmpt/libopenmpt/libopenmpt_impl.cpp
    openmpt/libopenmpt/libopenmpt_ext_impl.cpp
    ${OPENMPT_SOUNDLIB}
    ${OPENMPT_PLUGINS}
    ${OPENMPT_DMO}
    ${OPENMPT_SOUNDDSP}
)
target_include_directories(openmpt_static PUBLIC openmpt openmpt/src openmpt/common)
target_compile_definitions(openmpt_static PRIVATE LIBOPENMPT_BUILD MPT_WITH_ZLIB)
target_compile_options(openmpt_static PRIVATE -fexceptions -frtti -Wno-deprecated-declarations)

# Find Oboe package from gradle import.
find_package(oboe REQUIRED CONFIG)

add_library(${PROJECT_NAME} SHARED xmp-jni.cpp openmpt-jni.cpp oboe.cpp)

# Link libraries
target_link_libraries(${PROJECT_NAME} xmp_static openmpt_static oboe::oboe android log z)

# Include directories
# xmp-jni.cpp needs use of xmp.h and common.h
target_include_directories(${PROJECT_NAME} PRIVATE libxmp/include libxmp/src openmpt openmpt/src openmpt/common)
