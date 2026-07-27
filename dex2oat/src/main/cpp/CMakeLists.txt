cmake_minimum_required(VERSION 3.10)
project(dex2oat)

add_executable(dex2oat dex2oat.cpp)
add_library(oat_hook SHARED oat_hook.cpp)

OPTION(LSPLT_BUILD_SHARED OFF)
add_subdirectory(${VECTOR_ROOT}/external/lsplt/lsplt/src/main/jni external)

target_include_directories(oat_hook PUBLIC include)
target_include_directories(dex2oat PUBLIC include)
target_link_libraries(dex2oat log)
target_link_libraries(oat_hook log lsplt_static)

# Vector: fully disable LOGV/D/I/W/E/F/PLOGE (see include/logging.h).
target_compile_definitions(dex2oat PUBLIC LOG_DISABLED)
target_compile_definitions(oat_hook PUBLIC LOG_DISABLED)

if (DEFINED DEBUG_SYMBOLS_PATH)
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()
