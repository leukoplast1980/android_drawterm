# libdrawterm.so: unmodified upstream drawterm (the submodule at the repo
# root) plus the Android GUI layer here. Source lists come from upstream's
# Makefiles, so upstream additions and removals need no change here.

cmake_minimum_required(VERSION 3.22.1)
project(drawterm C)

set(DT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../drawterm" CACHE PATH "drawterm source tree")
get_filename_component(DT_ROOT "${DT_ROOT}" ABSOLUTE)
if(NOT EXISTS "${DT_ROOT}/cpu.c")
    message(FATAL_ERROR
        "drawterm sources not found in ${DT_ROOT}\n"
        "Run: git submodule update --init")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# C sources in the OFILES list of ${DT_ROOT}/<dir>/Makefile.
function(dt_sources dir outvar)
    file(READ "${DT_ROOT}/${dir}/Makefile" mk)
    set(mk "\n${mk}")
    string(REGEX REPLACE "\\\\\r?\n" " " mk "${mk}")
    string(REGEX MATCH "\nOFILES=[^\n]*" ofiles "${mk}")
    if(NOT ofiles)
        message(FATAL_ERROR "no OFILES in ${dir}/Makefile")
    endif()
    string(REPLACE "\nOFILES=" "" ofiles "${ofiles}")
    string(REPLACE "$(OS)" "posix" ofiles "${ofiles}")
    string(REPLACE "$(AUDIO)" "none" ofiles "${ofiles}")
    string(REPLACE "$(XOFILES)" "" ofiles "${ofiles}")
    string(REPLACE "$O" "c" ofiles "${ofiles}")
    separate_arguments(ofiles UNIX_COMMAND "${ofiles}")
    set(srcs "")
    foreach(f IN LISTS ofiles)
        if(f MATCHES "\\.c$")
            if(dir STREQUAL ".")
                list(APPEND srcs "${DT_ROOT}/${f}")
            else()
                list(APPEND srcs "${DT_ROOT}/${dir}/${f}")
            endif()
        endif()
    endforeach()
    set(${outvar} ${srcs} PARENT_SCOPE)
endfunction()

# Upstream's device table plus #N (devandroid.c), generated so upstream
# changes still flow in.
file(READ "${DT_ROOT}/kern/devtab.c" devtab)
if(NOT devtab MATCHES "androiddevtab")
    string(REPLACE "Dev *devtab[] = {"
        "extern Dev androiddevtab;\n\nDev *devtab[] = {" devtab "${devtab}")
    string(REGEX REPLACE "\n([ \t]*)0\n};" "\n\\1&androiddevtab,\n\\10\n};" devtab "${devtab}")
    if(NOT devtab MATCHES "&androiddevtab,")
        message(FATAL_ERROR "could not add androiddevtab to kern/devtab.c")
    endif()
endif()
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/devtab.c.new" "${devtab}")
configure_file("${CMAKE_CURRENT_BINARY_DIR}/devtab.c.new" "${CMAKE_CURRENT_BINARY_DIR}/devtab.c" COPYONLY)

set(DT_INCLUDES "${DT_ROOT}" "${DT_ROOT}/include" "${DT_ROOT}/kern")
# pthread mutexes rather than the spin-and-sleep fallback, as on Linux.
# Global because it changes the layout of Lock.
add_compile_definitions(PTHREAD _THREAD_SAFE)
# upstream warnings are not ours to fix
set(DT_UPSTREAM_FLAGS -w -fno-strict-aliasing -Dmain=dt_main)

set(DT_LIBS
    kern exportfs libauth libauthsrv libsec libmp
    libmemdraw libmemlayer libdraw libc libip posix-port)
set(DT_LIB_TARGETS "")
foreach(lib IN LISTS DT_LIBS)
    dt_sources(${lib} srcs)
    if(lib STREQUAL "kern")
        list(REMOVE_ITEM srcs "${DT_ROOT}/kern/devtab.c")
        list(APPEND srcs "${CMAKE_CURRENT_BINARY_DIR}/devtab.c")
    endif()
    string(REPLACE "-" "_" target "dt_${lib}")
    add_library(${target} STATIC ${srcs})
    target_include_directories(${target} PRIVATE ${DT_INCLUDES})
    target_compile_options(${target} PRIVATE ${DT_UPSTREAM_FLAGS})
    list(APPEND DT_LIB_TARGETS ${target})
endforeach()

# top-level objects (main.c, cpu.c, ...) are always linked
dt_sources(. DT_TOP_SRCS)
add_library(dt_top OBJECT ${DT_TOP_SRCS})
target_include_directories(dt_top PRIVATE ${DT_INCLUDES})
target_compile_options(dt_top PRIVATE ${DT_UPSTREAM_FLAGS})

add_library(drawterm SHARED
    $<TARGET_OBJECTS:dt_top>
    screen.c
    devandroid.c
    jni.c
    hooks.c)
target_include_directories(drawterm PRIVATE ${DT_INCLUDES} "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(drawterm PRIVATE
    -Wall -Wno-missing-braces -Wno-parentheses -Wno-unused-parameter
    -fno-strict-aliasing)
target_link_libraries(drawterm PRIVATE ${DT_LIB_TARGETS} android log m)
target_link_options(drawterm PRIVATE
    # see hooks.c
    "-Wl,--wrap=exit"
    "-Wl,--wrap=readcons"
    "-Wl,--wrap=so_connect"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/libdrawterm.map.txt"
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384")
set_target_properties(drawterm PROPERTIES
    LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/libdrawterm.map.txt")
