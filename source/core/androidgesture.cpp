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

#include "androidgesture.hpp"

#ifdef GAME_PLATFORM_ANDROID

#include "core/logger.hpp"
#include "core/position.hpp"
#include <SDL_system.h>
#include <math.h>
#include <string.h>

// SDL internal mouse state functions (src/events/SDL_mouse_c.h).
// Using them (instead of SDL_PushEvent) keeps SDL_GetMouseState(),
// EventConverter and the Android cursorPos() fix fully consistent,
// exactly like the original synthetic touch mouse did.
// SDL_MouseID is an internal type (Uint32) in SDL 2.0.4, not exposed publicly.
extern "C" {
extern int SDL_SendMouseMotion( SDL_Window* window, Uint32 mouseID, int relative, int x, int y );
extern int SDL_SendMouseButton( SDL_Window* window, Uint32 mouseID, Uint8 state, Uint8 button );
extern int SDL_SendMouseWheel( SDL_Window* window, Uint32 mouseID, int x, int y, SDL_MouseWheelDirection direction );
}

namespace
{
enum
{
  LONGPRESS_MS = 550,      // hold time that triggers a right click
  LONGPRESS_MOVE_PX = 14,  // max movement during a long-press
  DRAG_THRESHOLD_PX = 8,   // finger movement before a drag starts
  PINCH_STEP_PX = 26       // pinch distance change per zoom step
};

const SDL_EventType WAKE_EVENT = SDL_USEREVENT; // wakes the poll loop for long-press
}

class AndroidGestures::Impl
{
public:
  bool primaryActive;
  SDL_FingerID primaryId;
  Point startPos;      // window coords
  Point lastPos;       // window coords
  Uint32 downTime;
  float moved;
  bool mouseDown;      // virtual left button currently pressed
  bool longPressFired;
  bool pendingLongPress;

  bool secondaryActive;
  SDL_FingerID secondaryId;
  Point secondPos;
  float lastStepDist;  // distance at last zoom step

  SDL_TimerID timerId;

  Impl()
    : primaryActive( false ), primaryId( 0 ), startPos( 0, 0 ), lastPos( 0, 0 ),
      downTime( 0 ), moved( 0 ), mouseDown( false ), longPressFired( false ),
      pendingLongPress( false ), secondaryActive( false ), secondaryId( 0 ),
      secondPos( 0, 0 ), lastStepDist( 0 ), timerId( 0 )
  {
  }

  Point windowSize() const
  {
    int w = 1, h = 1;
    if( Android_Window ) SDL_GetWindowSize( Android_Window, &w, &h );
    if( w < 1 ) w = 1;
    if( h < 1 ) h = 1;
    return Point( w, h );
  }

  static Point toWindow( float nx, float ny, const Point& size )
  {
    return Point( (int)( nx * size.x() ), (int)( ny * size.y() ) );
  }

  float fingerDist() const
  {
    float dx = (float)( lastPos.x() - secondPos.x() );
    float dy = (float)( lastPos.y() - secondPos.y() );
    return sqrtf( dx * dx + dy * dy );
  }

  void sendWheel( int yDir )
  {
    if( Android_Window )
      SDL_SendMouseWheel( Android_Window, SDL_TOUCH_MOUSEID, 0, yDir, SDL_MOUSEWHEEL_NORMAL );
  }

  void sendMotion( const Point& p )
  {
    if( Android_Window )
      SDL_SendMouseMotion( Android_Window, SDL_TOUCH_MOUSEID, 0, p.x(), p.y() );
  }

  void sendButton( Uint8 state, Uint8 button, const Point& p )
  {
    if( !Android_Window )
      return;

    // keep SDL's virtual cursor in sync so that cursorPos() is correct
    SDL_SendMouseMotion( Android_Window, SDL_TOUCH_MOUSEID, 0, p.x(), p.y() );
    SDL_SendMouseButton( Android_Window, SDL_TOUCH_MOUSEID, state, button );
  }

  void cancelTimer()
  {
    if( timerId )
    {
      SDL_RemoveTimer( timerId );
      timerId = 0;
    }
  }

  static Uint32 timerCallback( Uint32 interval, void* param )
  {
    Impl* d = (Impl*)param;
    if( d->primaryActive && !d->moved && !d->longPressFired && !d->mouseDown )
    {
      d->pendingLongPress = true;
      SDL_Event ev;
      memset( &ev, 0, sizeof( ev ) );
      ev.type = WAKE_EVENT;
      SDL_PushEvent( &ev );
    }
    return 0; // one-shot
  }
};

AndroidGestures::AndroidGestures() : _d( new Impl )
{
  // Take full control of the touch pipeline: stop SDL from synthesizing
  // mouse events for the primary finger.
  SDL_SetHint( SDL_HINT_ANDROID_SEPARATE_MOUSE_AND_TOUCH, "1" );
  Logger::info( "AndroidGestures: touch pipeline initialized" );
}

AndroidGestures& AndroidGestures::instance()
{
  static AndroidGestures inst;
  return inst;
}

bool AndroidGestures::isOwnEvent( const SDL_Event& ev ) const
{
  return ev.type == WAKE_EVENT;
}

bool AndroidGestures::handle( const SDL_Event& ev )
{
  Impl& d = *_d;

  // wake-up event used to deliver a long-press when nothing else happens
  if( ev.type == WAKE_EVENT )
  {
    if( d.pendingLongPress )
    {
      d.pendingLongPress = false;
      d.longPressFired = true;
      d.sendButton( SDL_PRESSED, SDL_BUTTON_RIGHT, d.lastPos );
      Logger::info( "AndroidGestures: long-press -> RIGHT press" );
    }
    return true;
  }

  switch( ev.type )
  {
  case SDL_FINGERDOWN:
  {
    Point size = d.windowSize();
    Point p = Impl::toWindow( ev.tfinger.x, ev.tfinger.y, size );

    if( !d.primaryActive )
    {
      d.primaryActive = true;
      d.primaryId = ev.tfinger.fingerId;
      d.startPos = d.lastPos = p;
      d.downTime = ev.tfinger.timestamp ? ev.tfinger.timestamp : SDL_GetTicks();
      d.moved = 0;
      d.mouseDown = false;
      d.longPressFired = false;
      d.pendingLongPress = false;
      d.cancelTimer();
      d.timerId = SDL_AddTimer( LONGPRESS_MS, Impl::timerCallback, &d );
    }
    else if( !d.secondaryActive )
    {
      d.secondaryActive = true;
      d.secondaryId = ev.tfinger.fingerId;
      d.secondPos = p;
      d.lastStepDist = d.fingerDist();
    }
    return true;
  }

  case SDL_FINGERMOTION:
  {
    if( d.secondaryActive && ev.tfinger.fingerId == d.secondaryId )
    {
      Point size = d.windowSize();
      d.secondPos = Impl::toWindow( ev.tfinger.x, ev.tfinger.y, size );

      // pinch -> wheel (zoom)
      float dist = d.fingerDist();
      if( fabsf( dist - d.lastStepDist ) >= PINCH_STEP_PX )
      {
        d.sendWheel( dist > d.lastStepDist ? 1 : -1 );
        d.lastStepDist = dist;
      }
      return true;
    }

    if( d.primaryActive && ev.tfinger.fingerId == d.primaryId )
    {
      Point size = d.windowSize();
      Point p = Impl::toWindow( ev.tfinger.x, ev.tfinger.y, size );

      float dx = (float)( p.x() - d.lastPos.x() );
      float dy = (float)( p.y() - d.lastPos.y() );
      d.moved += sqrtf( dx * dx + dy * dy );
      d.lastPos = p;

      // start a virtual left drag after the movement threshold
      if( !d.mouseDown && !d.longPressFired && d.moved > DRAG_THRESHOLD_PX )
      {
        d.cancelTimer();
        d.mouseDown = true;
        d.sendButton( SDL_PRESSED, SDL_BUTTON_LEFT, d.startPos );
      }

      if( d.mouseDown )
        d.sendMotion( p );
    }
    return true;
  }

  case SDL_FINGERUP:
  {
    if( d.secondaryActive && ev.tfinger.fingerId == d.secondaryId )
    {
      d.secondaryActive = false;
      return true;
    }

    if( d.primaryActive && ev.tfinger.fingerId == d.primaryId )
    {
      d.cancelTimer();
      Point size = d.windowSize();
      Point p = Impl::toWindow( ev.tfinger.x, ev.tfinger.y, size );
      d.lastPos = p;

      if( d.longPressFired )
      {
        // finish the right click
        d.sendButton( SDL_RELEASED, SDL_BUTTON_RIGHT, p );
      }
      else if( d.mouseDown )
      {
        d.sendMotion( p );
        d.sendButton( SDL_RELEASED, SDL_BUTTON_LEFT, p );
      }
      else
      {
        // tap: synthesize a full left click at the release point
        d.sendMotion( p );
        d.sendButton( SDL_PRESSED, SDL_BUTTON_LEFT, p );
        d.sendButton( SDL_RELEASED, SDL_BUTTON_LEFT, p );
      }

      d.primaryActive = false;
      d.mouseDown = false;
      d.longPressFired = false;
      d.pendingLongPress = false;
      d.moved = 0;
      d.secondaryActive = false; // stray secondary finger without primary is meaningless
    }
    return true;
  }

  default:
    return false; // not a touch event: let EventConverter process it
  }
}

#endif //GAME_PLATFORM_ANDROID
