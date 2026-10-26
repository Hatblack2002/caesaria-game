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

#ifndef __CAESARIA_ANDROID_GESTURES_INCLUDE_HPP__
#define __CAESARIA_ANDROID_GESTURES_INCLUDE_HPP__

#include "core/platform.hpp"

#ifdef GAME_PLATFORM_ANDROID

#include <SDL.h>

// Touch gesture recognizer for the Android port.
//
// Translates raw SDL_FINGER* events (from the SDL Android backend) into the
// mouse events the engine already understands. The built-in SDL synthetic
// mouse is disabled (SDL_HINT_ANDROID_SEPARATE_MOUSE_AND_TOUCH=1) so that this
// class fully owns the touch pipeline.
//
// Gesture map (spec section 8):
//   - 1 finger tap          -> left mouse click (select, press buttons)
//   - 1 finger drag         -> left button drag (camera pan / build tracing)
//   - 1 finger long-press   -> right mouse press (cancel tool / context)
//   - 2 fingers drag        -> camera pan (left button drag, same as 1 finger)
//   - 2 fingers pinch       -> mouse wheel (map zoom)
class AndroidGestures
{
public:
  static AndroidGestures& instance();

  // Must be called for every polled SDL event BEFORE EventConverter.
  // Returns true when the event was a touch event that has been consumed
  // (its effects are delivered later as synthesized SDL mouse events).
  bool handle( const SDL_Event& ev );

  // Filter for the wake-up user event used by the long-press timer.
  bool isOwnEvent( const SDL_Event& ev ) const;

private:
  AndroidGestures();

  class Impl;
  Impl* _d;
};

#endif //GAME_PLATFORM_ANDROID
#endif //__CAESARIA_ANDROID_GESTURES_INCLUDE_HPP__
