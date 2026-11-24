cmake_minimum_required(VERSION 3.22.1)

project("Portal")

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=hidden -D_FORTIFY_SOURCE=2 -fstack-protector-strong")
find_package(dobby REQUIRED CONFIG)

add_library(portal SHARED
        main.cpp
        elf_util.cpp
        sensor_hook.cpp
        crash_recovery.cpp
        safety_gate.cpp
        csprng.cpp
        integrity.cpp)
# maps_filter.cpp and proc_maps_filter.cpp are excluded from all builds per
# risk model R4: their binary strings and hook capabilities increase the
# DuckDetector Memory/LSPosed native maps probe surface.
# To re-enable for local auditing, uncomment the next two lines.
#        maps_filter.cpp
#        proc_maps_filter.cpp
target_compile_definitions(portal PRIVATE "$<$<CONFIG:Debug>:KUNYUSHU_DEBUG>")
target_link_options(portal PRIVATE
    -Wl,--strip-all
    -Wl,-z,max-page-size=16384
    -Wl,-z,relro,-z,now)

target_link_libraries(portal android log)
target_link_libraries(portal dobby::dobby)
