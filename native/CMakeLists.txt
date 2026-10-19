# FHorizon Mobile - native core (fhcore).
#
# On Android (built by Gradle/AGP through externalNativeBuild) this produces
# libfhcore.so, the launcher's JNI library: XEX2 inspection, backend module
# (ELF) validation and the Vulkan capability probe.
#
# On a PC it builds the same inspectors as a command-line tool (fhinspect)
# and the host tests:
#   cmake -S native -B build/native -G Ninja -DFHCORE_VULKAN_HEADERS=<dir>
#   cmake --build build/native && ctest --test-dir build/native
#
# SPDX-License-Identifier: BSD-3-Clause
cmake_minimum_required(VERSION 3.22)
project(fhcore VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(FHCORE_VULKAN_HEADERS "" CACHE PATH
    "Vulkan-Headers include directory for host builds (Android uses the NDK's)")

add_library(fhcore_lib STATIC
  src/json.cpp
  src/xex.cpp
  src/elf_module.cpp
  src/module_scan.cpp
)
target_include_directories(fhcore_lib PUBLIC include)
target_compile_definitions(fhcore_lib PUBLIC FHCORE_VERSION="${PROJECT_VERSION}")
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(fhcore_lib PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()

set(FHCORE_HAVE_VULKAN OFF)
if(ANDROID)
  set(FHCORE_HAVE_VULKAN ON)
elseif(FHCORE_VULKAN_HEADERS AND EXISTS "${FHCORE_VULKAN_HEADERS}/vulkan/vulkan.h")
  set(FHCORE_HAVE_VULKAN ON)
endif()

if(FHCORE_HAVE_VULKAN)
  add_library(fhcore_vk STATIC src/vk_probe.cpp)
  target_link_libraries(fhcore_vk PUBLIC fhcore_lib ${CMAKE_DL_LIBS})
  if(NOT ANDROID)
    target_include_directories(fhcore_vk PRIVATE "${FHCORE_VULKAN_HEADERS}")
  endif()
endif()

if(ANDROID)
  add_library(fhcore SHARED src/jni_bridge.cpp)
  target_link_libraries(fhcore PRIVATE fhcore_lib fhcore_vk log)
  # Keep the C++ runtime inside libfhcore.so (ANDROID_STL=c++_static): the
  # backend module ships its own libc++_shared.so, and the launcher must not
  # put a second, different one into the process.
  target_link_options(fhcore PRIVATE
    "-Wl,--exclude-libs,ALL"
    "-Wl,-z,max-page-size=16384")
else()
  add_executable(fhinspect tools/fhinspect.cpp)
  target_link_libraries(fhinspect PRIVATE fhcore_lib)
  if(FHCORE_HAVE_VULKAN)
    target_link_libraries(fhinspect PRIVATE fhcore_vk)
    target_compile_definitions(fhinspect PRIVATE FHCORE_HAVE_VULKAN=1)
  endif()

  # Test fixtures: a tiny "module" of real shared libraries.
  set(FIXTURE_DIR "${CMAKE_CURRENT_BINARY_DIR}/fixture-module")
  add_library(fhtest_dep SHARED tests/fixtures/dep.c)
  add_library(fhtest_main SHARED tests/fixtures/main.c)
  add_library(fhtest_side SHARED tests/fixtures/side.c)
  target_link_libraries(fhtest_main PRIVATE fhtest_dep)
  foreach(fixture fhtest_dep fhtest_main fhtest_side)
    set_target_properties(${fixture} PROPERTIES
      LIBRARY_OUTPUT_DIRECTORY "${FIXTURE_DIR}"
      NO_SONAME OFF)
  endforeach()

  add_executable(fhcore_tests tests/test_main.cpp)
  target_link_libraries(fhcore_tests PRIVATE fhcore_lib)
  add_dependencies(fhcore_tests fhtest_dep fhtest_main fhtest_side)

  enable_testing()
  add_test(NAME fhcore_tests COMMAND fhcore_tests "${FIXTURE_DIR}")
endif()
