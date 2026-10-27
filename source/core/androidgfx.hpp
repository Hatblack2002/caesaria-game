// CaesarIA Android gfx bootstrap (header-only).
// On Android the model files reference archives through the ":/" vfs alias
// which does not resolve on this platform, so the remake zips (atlas
// packages) were never mounted and every atlas-based picture ended as
// Picture::getInvalid() -> black screen. This helper mounts every packaged
// zip with an absolute device path and registers its atlases in the
// PictureBank, restoring the same picture coverage the desktop build has.

#ifndef CAESARIA_ANDROID_GFX_HPP
#define CAESARIA_ANDROID_GFX_HPP

#ifdef GAME_PLATFORM_ANDROID

#include <SDL_system.h>
#include <set>
#include <string>

#include "vfs/filesystem.hpp"
#include "vfs/directory.hpp"
#include "vfs/path.hpp"
#include "vfs/file.hpp"
#include "gfx/picture_bank.hpp"
#include "core/logger.hpp"
#include "core/saveadapter.hpp"
#include "core/variant_map.hpp"
#include "core/stringarray.hpp"
#include "core/androidtelemetry.hpp"

namespace androidgfx
{

inline void mountZipAndRegisterAtlases( const vfs::Path& zipPath, int& nArch, int& nAtlas )
{
  vfs::ArchivePtr archive = vfs::FileSystem::instance().mountArchive( zipPath );
  if( archive.isNull() )
  {
    Logger::warning( "AndroidGfx: failed to mount " + zipPath.toString() );
    return;
  }

  nArch++;

  // each package carries an "info" file listing the atlases it contains
  vfs::NFile infoFile = archive->createAndOpenFile( "info" );
  if( !infoFile.isOpen() )
    return;

  VariantMap vm = config::load( infoFile );
  StringArray atlasNames = vm.get( "atlas" ).toStringArray();
  for( const auto& name : atlasNames )
  {
    // lazy registration: hashes only, textures are loaded on first use
    gfx::PictureBank::instance().addAtlas( name );
    nAtlas++;
  }
}

inline void mountGameArchives()
{
  int nArch = 0;
  int nAtlas = 0;
  std::set<std::string> mounted;

  const char* extBase = SDL_AndroidGetExternalStoragePath();
  std::string res = (extBase ? std::string( extBase ) : std::string()) + "/resources";

  // 1) all packaged gfx zips (pics_land, pics_gui, ...) with the remake atlases
  vfs::Directory gfxDir( res + "/gfx" );
  for( const auto& entry : gfxDir.entries().items() )
  {
    vfs::Path p = entry.fullpath;
    if( p.extension() == ".zip" && mounted.insert( p.absolutePath().toString() ).second )
    {
      mountZipAndRegisterAtlases( p, nArch, nAtlas );
    }
  }

  // 2) flat picture pack: every individual png of the resources set
  //    (entries are bare file names, so tryLoadPicture finds them by name)
  vfs::Path flatPack( res + "/pics_all.zip" );
  if( flatPack.exist() )
  {
    mountZipAndRegisterAtlases( flatPack, nArch, nAtlas );
    Logger::info( "AndroidGfx: flat picture pack mounted" );
  }
  else
  {
    Logger::warning( "AndroidGfx: flat picture pack missing at " + flatPack.toString() );
  }

  std::string statLine = "gfx_mounted:" + std::to_string( nArch ) + "archives/"
                         + std::to_string( nAtlas ) + "atlases";
  Logger::info( "AndroidGfx: " + statLine );
  androidtelemetry::setStage( statLine );
}

} // namespace androidgfx

#endif // GAME_PLATFORM_ANDROID

#endif // CAESARIA_ANDROID_GFX_HPP
