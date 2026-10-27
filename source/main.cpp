// This file is part of CaesarIA.
//
// CaesarIA is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// CaesarIA is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with CaesarIA.  If not, see <http://www.gnu.org/licenses/>.
//
// Copyright 2012-2014 Dalerank, dalerankn8@gmail.com

#include "core/utils.hpp"
#include "core/exception.hpp"
#include "core/androidtelemetry.hpp"
#ifdef GAME_PLATFORM_ANDROID
#include "core/androidgfx.hpp"
#endif
#include "vfs/directory.hpp"
#include "game/settings.hpp"
#include "game/game.hpp"
#include "core/logger.hpp"
#include "core/stacktrace.hpp"
#include "core/osystem.hpp"
#include "scene/constants.hpp"
#include "steam.hpp"

#ifdef GAME_PLATFORM_WIN
  #undef main
#endif

#ifdef GAME_PLATFORM_ANDROID
#include <SDL.h>
#include <SDL_system.h>
#endif

int main(int argc, char* argv[])
{
  crashhandler::install();

  vfs::Directory workdir;
#ifdef GAME_PLATFORM_ANDROID
  workdir  = vfs::Path( SDL_AndroidGetExternalStoragePath() );
#else
  workdir = vfs::Path( argv[0] ).directory();
#endif

  game::Settings& options = game::Settings::instance();
  Logger::registerWriter(Logger::consolelog, "");

  options.setwdir(workdir.toString());
  bool wdirChanged = options.checkwdir( argv, argc );
  if (wdirChanged)
    workdir = SETTINGS_STR(workDir);

  options.resetIfNeed(argv, argc);
  Logger::registerWriter(Logger::filelog, workdir.toString());

  Logger::info("Setting workdir to " + SETTINGS_STR(workDir));

  Logger::info("Loading game settings");
  if (options.haveLastConfig())
    options.loadLastConfig();

  options.checkCmdOptions(argv, argc);
  options.checkC3present();

  if (!KILLSWITCH(verbose))
  {
    Logger::addFilter(LogWriter::info);
    Logger::addFilter(LogWriter::debug);
  }

  std::string systemLang = SETTINGS_STR(language);

  if (steamapi::available())
  {
    if (!steamapi::connect())
    {
      Logger::fatal("Failed to connect to steam");
      return EXIT_FAILURE;
    }

    if (systemLang.empty())
      systemLang = steamapi::language();
  }

  options.changeSystemLang(systemLang);

  Logger::info("Language set to " + SETTINGS_STR(language));
  Logger::info("Using native C3 resources from " + SETTINGS_STR(c3gfx));
  Logger::info("Cell width set to " + SETTINGS_VALUE(cellw).toInt());

  try
  {
#ifdef GAME_PLATFORM_ANDROID
    // mount the packaged zip archives (remake atlases + flat picture pack)
    // with absolute device paths before the resource loader runs; the ":/"
    // vfs alias used by the model files does not resolve on Android
    androidgfx::mountGameArchives();
#endif

    Game game;
    game.initialize();

#ifdef GAME_PLATFORM_ANDROID
    // Native launcher (LauncherActivity) requests a direct action:
    // -action new      -> start tutorial city, skip engine main menu
    // -action continue -> load last save, skip engine main menu
    // -action loadfile -> load file passed via -loadfile <path>
    // anything else    -> show engine main menu (used for -action options)
    try
    {
      std::string launcherAction = game::Settings::get( "action" ).toString();
      if( launcherAction == "new" || launcherAction == "continue" || launcherAction == "loadfile" )
      {
        std::string cityFile;
        if( launcherAction == "new" )
          cityFile = ":/missions/tutorial.mission";
        else
          cityFile = game::Settings::get( "loadfile" ).toString();

        if( launcherAction == "continue" && cityFile.empty() )
          cityFile = SETTINGS_STR(lastGame);

        Logger::info("Launcher action '" + launcherAction + "' loading '" + cityFile + "'");
        bool loaded = false;
        if( !cityFile.empty() )
        {
          game.reset();
          loaded = game.load( cityFile );
          if( loaded )
          {
            androidtelemetry::setStage( "city_loaded" );
            game.setNextScreen( scene::SCREEN_GAME );
          }
        }

        if( !loaded )
        {
          // never leave the engine without a screen: fall back to the
          // main menu so the user still gets a usable application
          Logger::warning("Launcher action '" + launcherAction + "' failed, fallback to main menu");
          game.setNextScreen( scene::SCREEN_MENU );
        }
      }
    }
    catch( Exception& e )
    {
      Logger::warning("Launcher action failed: " + e.getDescription() + ", fallback to main menu");
      game.setNextScreen( scene::SCREEN_MENU );
    }
    catch( ... )
    {
      Logger::warning("Launcher action failed: unknown exception, fallback to main menu");
#ifdef GAME_PLATFORM_ANDROID
      androidtelemetry::noteFailure( "launcher action: unknown exception" );
#endif
      game.setNextScreen( scene::SCREEN_MENU );
    }
#endif

#ifdef GAME_PLATFORM_ANDROID
    // a throw inside any screen (city render, gui, ...) must never kill the
    // whole application: fall back to the engine menu and keep the app alive
    int consecutiveFailures = 0;
    int forcedMenuFallbacks = 0;
    androidtelemetry::setStage( "exec_started" );
    while( consecutiveFailures < 3 )
    {
      bool frameOk = true;
      try
      {
        if( !game.exec() )
        {
          // a SCREEN_QUIT arriving while the player never reached the engine
          // menu is a bug (unexpected Level result -> InGame destructor), not
          // a user action: show the menu instead of exiting silently
          if( forcedMenuFallbacks < 1 )
          {
            forcedMenuFallbacks++;
            frameOk = false;
            Logger::warning( "Engine requested quit outside the main menu, forcing main menu fallback" );
            androidtelemetry::noteFailure( "unexpected quit request, forced menu fallback" );
            androidtelemetry::setStage( "forced_menu_fallback" );
            game.setNextScreen( scene::SCREEN_MENU );
            continue;
          }

          androidtelemetry::setStage( "exec_quit" );
          break;
        }
        consecutiveFailures = 0;
      }
      catch( Exception& e )
      {
        frameOk = false;
        Logger::warning("Screen error: " + e.getDescription() + ", fallback to main menu");
        androidtelemetry::noteFailure( "screen: " + e.getDescription() );
        game.setNextScreen( scene::SCREEN_MENU );
      }
      catch( ... )
      {
        frameOk = false;
        Logger::warning("Screen error: unknown exception, fallback to main menu");
        androidtelemetry::noteFailure( "screen: unknown exception" );
        game.setNextScreen( scene::SCREEN_MENU );
      }

      if( !frameOk )
        consecutiveFailures++;
    }
#else
    while( game.exec() );
#endif

    game.destroy();
  }
  catch( Exception& e )
  {
    Logger::fatal("Critical error: " + e.getDescription());
#ifdef GAME_PLATFORM_ANDROID
    androidtelemetry::noteFailure( "main fatal: " + e.getDescription() );
#endif
    crashhandler::printstack();
  }
  catch( ... )
  {
    Logger::fatal("Critical error: unknown exception");
#ifdef GAME_PLATFORM_ANDROID
    androidtelemetry::noteFailure( "main fatal: unknown exception" );
#endif
    crashhandler::printstack();
  }

  if (steamapi::available())
    steamapi::close();

  crashhandler::remove();

  return 0;
}
