# Android build of CaesarIA into a single shared library `main` (libmain.so).
# Mirrors the desktop build (root CMakeLists.txt + source/CMakeLists.txt) but
# replaces the SDL2mini desktop backend with its Android backend.
cmake_minimum_required(VERSION 3.10)
project(caesaria-main C CXX)

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..")
set(DEP_SOURCE_DIR "${REPO_ROOT}/dep")
set(SRC_DIR "${REPO_ROOT}/source")
set(STEAM_DIR "${REPO_ROOT}/steam")

message(STATUS "CaesarIA android: REPO_ROOT=${REPO_ROOT}")

# ---------------------------------------------------------------- version.hpp
set(REV_NUMBER 0)
if(EXISTS "${REPO_ROOT}/.git")
  find_package(Git QUIET)
  if(GIT_FOUND)
    execute_process(
      COMMAND ${GIT_EXECUTABLE} rev-list --count HEAD
      WORKING_DIRECTORY ${REPO_ROOT}
      OUTPUT_VARIABLE REV_NUMBER
      OUTPUT_STRIP_TRAILING_WHITESPACE
      ERROR_QUIET)
  endif()
endif()
if(NOT REV_NUMBER OR REV_NUMBER STREQUAL "")
  set(REV_NUMBER 0)
endif()
configure_file("${REPO_ROOT}/cmake/Modules/version.hpp.in.cmake"
               "${SRC_DIR}/version.hpp" @ONLY)
message(STATUS "CaesarIA revision: ${REV_NUMBER}")

# ---------------------------------------------------------------- dependencies
# order matters: zlib before libpng/minizip, sdl2mini before mixer/ttf

# zlib
set(ZLIB_NAME "zlib")
set(ZLIB_HOME "${DEP_SOURCE_DIR}/${ZLIB_NAME}")
add_subdirectory(${ZLIB_HOME} zlib.build)

# freetype
set(FREETYPELIB_NAME "freetype")
set(FREETYPELIB_HOME "${DEP_SOURCE_DIR}/${FREETYPELIB_NAME}")
add_subdirectory(${FREETYPELIB_HOME} freetype.build)

# sdl2mini (static; its CMakeLists.txt has a dedicated ANDROID branch)
set(SDL2MINI_NAME "sdl2mini")
set(SDL2MINI_HOME "${DEP_SOURCE_DIR}/${SDL2MINI_NAME}")
add_subdirectory(${SDL2MINI_HOME} sdl2mini.build)

# bzip2 / lzma / aes / smk
set(BZIPLIB_NAME "bzip2")
set(BZIPLIB_HOME "${DEP_SOURCE_DIR}/${BZIPLIB_NAME}")
add_subdirectory(${BZIPLIB_HOME} bzip2.build)

set(LZMALIB_NAME "lzma")
set(LZMALIB_HOME "${DEP_SOURCE_DIR}/${LZMALIB_NAME}")
add_subdirectory(${LZMALIB_HOME} lzma.build)

set(AESLIB_NAME "aes")
set(AESLIB_HOME "${DEP_SOURCE_DIR}/${AESLIB_NAME}")
add_subdirectory(${AESLIB_HOME} aes.build)

set(SMKLIB_NAME "smk")
set(SMKLIB_HOME "${DEP_SOURCE_DIR}/${SMKLIB_NAME}")
add_subdirectory(${SMKLIB_HOME} smk.build)

# libpng (vendored copy ships no arm/ SIMD sources -> disable NEON paths)
add_definitions(-DPNG_ARM_NEON_OPT=0)
set(PNGLIB_NAME "libpng")
set(PNGLIB_HOME "${DEP_SOURCE_DIR}/${PNGLIB_NAME}")
add_subdirectory(${PNGLIB_HOME} libpng.build)
remove_definitions(-DPNG_ARM_NEON_OPT=0)
# ttf expects the variable name used by the desktop build
set(LIBPNG_INCLUDE_DIR ${PNG_INCLUDE_DIR})

# minizip
set(MINIZIP_NAME "minizip")
set(MINIZIP_HOME "${DEP_SOURCE_DIR}/${MINIZIP_NAME}")
add_subdirectory(${MINIZIP_HOME} minizip.build)

# litehtml
set(LITEHTML_NAME "litehtml")
set(LITEHTML_HOME "${DEP_SOURCE_DIR}/${LITEHTML_NAME}")
add_subdirectory(${LITEHTML_HOME} litehtml.build)

# SDL2_mixer (with tremor/vorbis vendored)
set(MIXER_NAME "mixer")
set(MIXER_HOME "${DEP_SOURCE_DIR}/${MIXER_NAME}")
add_subdirectory(${MIXER_HOME} mixer.build)

# SDL2_ttf
set(TTFLIB_NAME "ttf")
set(TTFLIB_HOME "${DEP_SOURCE_DIR}/${TTFLIB_NAME}")
add_subdirectory(${TTFLIB_HOME} ttf.build)

# picoc (vendored mujs)
set(PICOC_NAME "picoc")
set(PICOC_HOME "${DEP_SOURCE_DIR}/${PICOC_NAME}")
add_subdirectory(${PICOC_HOME} picoc.build)

# ---------------------------------------------------------------- game sources
set(GAME_SOURCES "")
file(GLOB TOP_SOURCES "${SRC_DIR}/*.cpp")
list(APPEND GAME_SOURCES ${TOP_SOURCES})

foreach(GAME_SUBDIR events core pathway font gui game city good vfs gfx sound
                    objects walker world scene thread layers religion scripting)
  file(GLOB DIR_SOURCES "${SRC_DIR}/${GAME_SUBDIR}/*.cpp")
  list(APPEND GAME_SOURCES ${DIR_SOURCES})
endforeach()

# steam stub (USE_STEAM is OFF: compiles to no-ops)
file(GLOB STEAM_SOURCES "${STEAM_DIR}/*.cpp")
list(APPEND GAME_SOURCES ${STEAM_SOURCES})

# SDL JNI entry point: Java_org_libsdl_app_SDLActivity_nativeInit -> SDL_main
list(APPEND GAME_SOURCES "${DEP_SOURCE_DIR}/sdl2mini/src/main/android/SDL_android_main.c")

add_library(main SHARED ${GAME_SOURCES})

set_target_properties(main PROPERTIES
  POSITION_INDEPENDENT_CODE ON)

target_include_directories(main PRIVATE
  ${SRC_DIR}
  ${SRC_DIR}/include
  ${DEP_SOURCE_DIR}
  ${STEAM_DIR}
  ${SDL2MINI_INCLUDE_DIR}
  ${MIXER_INCLUDE_DIR}
  ${PNG_INCLUDE_DIR}
  ${ZLIB_INCLUDE_DIR}
  ${LITEHTML_INCLUDE_DIR}
  ${AES_INCLUDE_DIR}
  ${BZIP_INCLUDE_DIR}
  ${LZMA_INCLUDE_DIR}
  ${SMK_INCLUDE_DIR}
  ${SDL2_TTF_INCLUDE_DIR}
  ${FREETYPE_INCLUDE_DIR}
  ${MINIZIP_INCLUDE_DIR}
  ${PICOC_INCLUDE_DIR}
)

target_compile_definitions(main PRIVATE
  GAME_USE_SDL_MIXER=1
  GAME_USE_FRAMEBUFFER=1
  GAME_USE_SHADERS=1
  GAME_COMPILER_NAME="clang-android"
)

# proven static link order from the reference build
target_link_libraries( main
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
