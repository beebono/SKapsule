cmake_minimum_required(VERSION 3.22.1)
project(sklauncher LANGUAGES C CXX)

add_library(sklauncher SHARED
    sklauncher.c
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(sklauncher
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
)
