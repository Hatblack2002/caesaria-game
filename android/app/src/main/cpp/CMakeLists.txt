# CaesarIA Android build (arm64-v8a)
# Builds all vendored dependencies from source and the game itself as a
# shared library `main` (libmain.so) to be loaded by SDLActivity.
cmake_minimum_required(VERSION 3.14)
project(caesaria-android C CXX)

# this file lives at <repo>/android/app/src/main/cpp/CMakeLists.txt
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../" ABSOLUTE)

set(DEP_SOURCE_DIR "${REPO_ROOT}/dep")
set(GAME_SOURCE_DIR "${REPO_ROOT}/source")

message(STATUS "REPO_ROOT = ${REPO_ROOT}")

##############################
# Global build settings
##############################
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_STANDARD 99)

add_definitions(-DGAME_COMPILER_NAME="clang")
add_definitions(-DGL_GLEXT_PROTOTYPES)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

##############################
# Vendored dependencies
##############################
# zlib (needed by libpng, minizip)
add_subdirectory(${DEP_SOURCE_DIR}/zlib zlib)

# bzip2
add_subdirectory(${DEP_SOURCE_DIR}/bzip2 bzip2)

# lzma
add_subdirectory(${DEP_SOURCE_DIR}/lzma lzma)

# aes
add_subdirectory(${DEP_SOURCE_DIR}/aes aes)

# smk (Smacker video)
add_subdirectory(${DEP_SOURCE_DIR}/smk smk)

# minizip
add_subdirectory(${DEP_SOURCE_DIR}/minizip minizip)

# litehtml (gui rich text)
add_subdirectory(${DEP_SOURCE_DIR}/litehtml litehtml)

# SDL2 (sdl2mini fork, has native Android branch)
add_subdirectory(${DEP_SOURCE_DIR}/sdl2mini sdl2mini)

# SDL2_mixer (with tremor ogg)
add_subdirectory(${DEP_SOURCE_DIR}/mixer mixer)

# picoc (script interpreter)
add_subdirectory(${DEP_SOURCE_DIR}/picoc picoc)

# libpng (NEON intrinsics path disabled: vendored libpng has no arm/ sources)
add_definitions(-DPNG_ARM_NEON_OPT=0)
add_subdirectory(${DEP_SOURCE_DIR}/libpng libpng)
remove_definitions(-DPNG_ARM_NEON_OPT=0)

# freetype
set(FREETYPELIB_NAME "freetype")
add_subdirectory(${DEP_SOURCE_DIR}/freetype freetype)

# SDL2_ttf
add_subdirectory(${DEP_SOURCE_DIR}/ttf ttf)

##############################
# Game version header
##############################
set(rev_number "0")
if(EXISTS "${REPO_ROOT}/.git")
  execute_process(
    COMMAND git rev-list HEAD --count
    WORKING_DIRECTORY "${REPO_ROOT}"
    OUTPUT_VARIABLE rev_number
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
  )
  if(NOT rev_number)
    set(rev_number "0xff")
  endif()
endif()
configure_file(
  "${REPO_ROOT}/cmake/Modules/version.hpp.in.cmake"
  "${GAME_SOURCE_DIR}/version.hpp"
  @ONLY
)
set(rev_number_cached "${rev_number}" CACHE INTERNAL "Cached commit-number")

##############################
# Game sources -> libmain.so
##############################
file(GLOB GAME_ROOT_SOURCES      "${GAME_SOURCE_DIR}/*.cpp")
file(GLOB GAME_CITY_SOURCES      "${GAME_SOURCE_DIR}/city/*.cpp")
file(GLOB GAME_CORE_SOURCES      "${GAME_SOURCE_DIR}/core/*.cpp")
file(GLOB GAME_EVENTS_SOURCES    "${GAME_SOURCE_DIR}/events/*.cpp")
file(GLOB GAME_FONT_SOURCES      "${GAME_SOURCE_DIR}/font/*.cpp")
file(GLOB GAME_GAME_SOURCES      "${GAME_SOURCE_DIR}/game/*.cpp")
file(GLOB GAME_GFX_SOURCES       "${GAME_SOURCE_DIR}/gfx/*.cpp")
file(GLOB GAME_GOOD_SOURCES      "${GAME_SOURCE_DIR}/good/*.cpp")
file(GLOB GAME_GUI_SOURCES       "${GAME_SOURCE_DIR}/gui/*.cpp")
file(GLOB GAME_LAYERS_SOURCES    "${GAME_SOURCE_DIR}/layers/*.cpp")
file(GLOB GAME_OBJECTS_SOURCES   "${GAME_SOURCE_DIR}/objects/*.cpp")
file(GLOB GAME_PATHWAY_SOURCES   "${GAME_SOURCE_DIR}/pathway/*.cpp")
file(GLOB GAME_RELIGION_SOURCES  "${GAME_SOURCE_DIR}/religion/*.cpp")
file(GLOB GAME_SCENE_SOURCES     "${GAME_SOURCE_DIR}/scene/*.cpp")
file(GLOB GAME_SCRIPTING_SOURCES "${GAME_SOURCE_DIR}/scripting/*.cpp")
file(GLOB GAME_SOUND_SOURCES     "${GAME_SOURCE_DIR}/sound/*.cpp")
file(GLOB GAME_STEAM_SOURCES    "${REPO_ROOT}/steam/*.cpp")
file(GLOB GAME_THREAD_SOURCES    "${GAME_SOURCE_DIR}/thread/*.cpp")
file(GLOB GAME_VFS_SOURCES       "${GAME_SOURCE_DIR}/vfs/*.cpp")
file(GLOB GAME_WALKER_SOURCES    "${GAME_SOURCE_DIR}/walker/*.cpp")
file(GLOB GAME_WORLD_SOURCES     "${GAME_SOURCE_DIR}/world/*.cpp")

set(GAME_SOURCES
  ${GAME_ROOT_SOURCES}
  ${GAME_CORE_SOURCES}
  ${GAME_CITY_SOURCES}
  ${GAME_EVENTS_SOURCES}
  ${GAME_FONT_SOURCES}
  ${GAME_GAME_SOURCES}
  ${GAME_GFX_SOURCES}
  ${GAME_GOOD_SOURCES}
  ${GAME_GUI_SOURCES}
  ${GAME_LAYERS_SOURCES}
  ${GAME_OBJECTS_SOURCES}
  ${GAME_PATHWAY_SOURCES}
  ${GAME_RELIGION_SOURCES}
  ${GAME_SCENE_SOURCES}
  ${GAME_SCRIPTING_SOURCES}
  ${GAME_SOUND_SOURCES}
  ${GAME_STEAM_SOURCES}
  ${GAME_THREAD_SOURCES}
  ${GAME_VFS_SOURCES}
  ${GAME_WALKER_SOURCES}
  ${GAME_WORLD_SOURCES}
  # SDL android bootstrap: JNI entry point calling SDL_main
  ${DEP_SOURCE_DIR}/sdl2mini/src/main/android/SDL_android_main.c
)

add_library(main SHARED ${GAME_SOURCES})

target_include_directories(main PRIVATE
  ${GAME_SOURCE_DIR}
  ${GAME_SOURCE_DIR}/include
  ${REPO_ROOT}/steam
  ${DEP_SOURCE_DIR}
  ${DEP_SOURCE_DIR}/sdl2mini/include
  ${DEP_SOURCE_DIR}/mixer
  ${DEP_SOURCE_DIR}/ttf
  ${DEP_SOURCE_DIR}/freetype/include
  ${DEP_SOURCE_DIR}/picoc
  ${DEP_SOURCE_DIR}/litehtml/include
  ${DEP_SOURCE_DIR}/libpng
  ${DEP_SOURCE_DIR}/zlib
  ${DEP_SOURCE_DIR}/minizip
  ${DEP_SOURCE_DIR}/smk
  ${DEP_SOURCE_DIR}/lzma
  ${DEP_SOURCE_DIR}/bzip2
  ${DEP_SOURCE_DIR}/aes
)

# link order matters for static archives: dependents first
target_link_libraries(main
  ${SDL2MINI_LIBRARY}
  ${MIXER_LIBRARY}
  ${SDL2_TTF_LIBRARY}
  ${FREETYPE_LIBRARY}
  ${PICOC_LIBRARY}
  ${SMK_LIBRARY}
  ${AES_LIBRARY}
  ${BZIP_LIBRARY}
  ${LZMA_LIBRARY}
  ${MINIZIP_LIBRARY}
  ${PNG_LIBRARY}
  ${ZLIB_LIBRARY}
  ${LITEHTML_LIBRARY}
  log
  android
  GLESv1_CM
  GLESv2
  z
)

# exceptions + rtti are enabled by default with NDK toolchain, make sure
target_compile_options(main PRIVATE -frtti -fexceptions -Wall -Wno-unused-variable -Wno-unused-but-set-variable -Wno-deprecated-declarations)
