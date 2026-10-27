// CaesarIA Android telemetry bridge (header-only).
// The native engine records fatal failures into a marker file that survives
// process death; the Java CrashReporter picks it up on the next application
// start and uploads it to the telemetry repository (spec section 7).

#ifndef CAESARIA_ANDROID_TELEMETRY_HPP
#define CAESARIA_ANDROID_TELEMETRY_HPP

#include <string>

#ifdef GAME_PLATFORM_ANDROID

#include <SDL_system.h>
#include <sys/stat.h>
#include <fstream>
#include <cstdlib>

namespace androidtelemetry
{

inline std::string errorFilePath()
{
  const char* base = SDL_AndroidGetExternalStoragePath();
  if( base == nullptr || base[0] == 0 )
    return std::string();

  std::string dir = std::string( base ) + "/telemetry";
  mkdir( dir.c_str(), 0755 ); // exists -> harmless
  return dir + "/engine_error.txt";
}

/** Append a failure note (called before unwinding / on fatal errors). */
inline void noteFailure( const std::string& what )
{
  std::string p = errorFilePath();
  if( p.empty() )
    return;

  std::ofstream f( p.c_str(), std::ios::app );
  if( f.is_open() )
    f << what << "\n";
}

} // namespace androidtelemetry

#endif // GAME_PLATFORM_ANDROID

#endif // CAESARIA_ANDROID_TELEMETRY_HPP
