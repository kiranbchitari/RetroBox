cmake_minimum_required(VERSION 3.22.1)
project(retrobox_host CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(retrobox_host SHARED host.cpp renderer.cpp jni_bridge.cpp)
target_include_directories(retrobox_host PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(retrobox_host PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)
target_link_libraries(retrobox_host log GLESv2 dl)
