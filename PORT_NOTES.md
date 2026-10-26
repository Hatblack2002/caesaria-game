# PORT_NOTES — CaesarIA → Android (ARM64)

Registro de decisiones, errores resueltos y estado por fase del port de
CaesarIA (remake open source de Caesar III) a Android ARM64.

Repositorio de trabajo: fork `Hatblack2002/caesaria-game` de `dalerank/caesaria-game`.
Build: **GitHub Actions** (`.github/workflows/android.yml`), APK firmado arm64-v8a.
APK id: `net.dalerank.caesaria`, minSdk 24, targetSdk 34.

---

## Arquitectura

* **Una sola librería nativa** `libmain.so` (módulo `main`): motor + todas las
  dependencias vendorizadas enlazadas estáticamente (sdl2mini, mixer+tremor,
  ttf, freetype, libpng, zlib, bzip2, lzma, aes, smk, minizip, litehtml,
  picoc/mujs) + stub de Steam. `CaesarIA.getLibraries()` devuelve `{"main"}`.
* **Dos Activities** (especificación §12):
  * `LauncherActivity` (nativa, `MAIN/LAUNCHER`): splash con `inicio.jpg`,
    menú de 5 botones, comprobación de recursos, selector SAF.
  * `CaesarIA extends SDLActivity` (`exported=false`): sólo se abre desde el
    launcher con extras `action` (`new|continue|loadfile|options`) y
    `loadfile`.
* **Paso de acciones al motor**: `getArguments()` (SDLActivity.java:94) →
  `SDL_android_main.c` convierte cada String en `argv` → `main()` del motor.
  `Settings::checkCmdOptions` ya acepta `-key value` genérico, así que el
  parche de `main.cpp` lee `Settings::get("action")`:
  * `new` → `game.reset(); game.load(":/missions/tutorial.mission"); setNextScreen(SCREEN_GAME)`
  * `continue` → ídem con `lastGame` (o `-loadfile`)
  * `loadfile` → ídem con el fichero elegido en el selector nativo
  * `options` (o ninguno) → menú del motor (Lobby), donde están las opciones
* **El menú propio del motor se omite** cuando hay acción explícita; al salir
  del motor, `main()` retorna → SDLActivity hace `finish()` automáticamente
  (SDLActivity.handleNativeExit) → el usuario vuelve al LauncherActivity.
* **Recursos**: el APK empaqueta `bin/resources` (caché de gráficos, 146 MB)
  como assets; `LauncherActivity` y `CaesarIA` los extraen (idempotente,
  marcador `.caesaria_assets_rev`) a `getExternalFilesDir(null)/resources`.
  El motor usa `SDL_AndroidGetExternalStoragePath()` como workdir →
  saves en `<ext>/saves/*.oc3save`, settings en `<ext>/resources/settings.model`.
* **Telemetría** (§7): `CrashReporter` captura `logcat -d -t 3000` de la
  sesión anterior al arrancar, registra `UncaughtExceptionHandler`, y sube
  crashes + logcat a Issues del repo privado de logs vía API de GitHub.
  Credenciales: `assets/telemetry.properties` escrito por Actions desde los
  secrets `TELEMETRY_TOKEN`/`TELEMETRY_REPO` (nunca en el repo público).
  Cada transición del launcher y del motor se registra con el tag
  `CAESARIA_LAUNCHER` (incluido en el logcat subido).

## Controles táctiles (§8) — implementación quirúrgica

Nuevos archivos `source/core/androidgesture.{hpp,cpp}` + hook de 6 líneas en
`SdlEngine::haveEvent` + `SDL_HINT_ANDROID_SEPARATE_MOUSE_AND_TOUCH=1`:

| Gesto | Evento sintetizado | Uso |
|---|---|---|
| 1 dedo toque | LEFT down+up (click) | seleccionar, botones |
| 1 dedo arrastre (>8 px) | LEFT drag (motion) | desplazar cámara / trazar calles |
| 1 dedo pulsación larga (550 ms) | RIGHT press (+release al soltar) | cancelar herramienta |
| 2 dedos arrastre | LEFT drag | desplazar cámara |
| 2 dedos pellizco (Δ26 px) | MOUSEWHEEL ±1 | zoom (`Camera::changeZoom`) |

Detalles quirúrgicos:

* Se desactiva el ratón sintético integrado de SDL (hint separado) y el
  reconocedor usa las funciones internas `SDL_SendMouseMotion/Button/Wheel`
  (mismas que usaba el sintético) → `SDL_GetMouseState`, `EventConverter` y el
  fix `cursorPos()` de `SdlEngine::haveEvent` quedan 100% consistentes.
* El drag no envía LEFT down hasta superar 8 px → los toques no disparan
  construcción accidental y los botones responden al soltar.
* El motor ya pan-aba con botón izquierdo arrastrado (`Layer::onMouseMoved`)
  y ya tenía zoom por rueda (`CityRenderer` → `changeZoom`) → no se tocó nada
  del motor de renderizado; sólo se traducen eventos.
* `changeZoom` respeta la opción `zoomInvert` del motor.
* Teclado virtual: el motor no llama `SDL_StartTextInput`; los cuadros de
  texto del motor (nombre de partida) se documentan como pendiente.

## Errores de compilación resueltos (parches aplicados)

1. `dep/zlib/gzguts.h` — `lseek/read/close` implícitos en Linux/Android →
   `#include <unistd.h>` bajo `!defined(_WIN32) && !defined(_MSC_VER)`.
2. `dep/sdl2mini/src/video/SDL_egl.c` — `ANativeWindow_setBuffersGeometry`
   sin declarar → `#include <android/native_window.h>` bajo `__ANDROID__`.
3. `dep/aes/sha2.h` — `typedef int64_t sha2_64t` produce narrowing en C++17 →
   `typedef uint64_t sha2_64t`.
4. `dep/mixer/CMakeLists.txt` — `-Werror=incompatible-function-pointer-types`
   (clang NDK r27) en load_ogg.c → `-Wno-error=...` sólo en ANDROID.
5. `dep/ttf/SDL_ttf.c:1729` — `TTF_CHECKPOINTER(text, NULL)` devuelve puntero
   en función `int` → `TTF_CHECKPOINTER(text, 0)`.
6. `source/core/osystem.hpp` — los macros predefinidos `unix`/`linux` chocan
   con los valores del enum `OSystem::Type` → `#undef` protegido.
7. `source/gfx/picture_bank.cpp` — comparación `texture() > 0` (puntero vs
   entero, error en C++17) → `!= nullptr`.
8. `source/objects/house.cpp` (~1498) — `s[Service::x] > 0` ídem → `!= nullptr`.
9. `dep/libpng` vendorizado no trae los ficheros `arm/` (SIMD) →
   `-DPNG_ARM_NEON_OPT=0` alrededor de su `add_subdirectory`.
10. `dep/sdl2mini/CMakeLists.txt` — no tenía rama ANDROID (el fork es SDL
    2.0.4): añadida rama `if(ANDROID)` que compila los backends
    video/android, core/android, audio/android, filesystem/android,
    joystick/android, thread/pthread, timer/unix, loadso/dlopen,
    render/{software,opengl,opengles,opengles2}, haptic/dummy, dynapi y
    enlaza `log android GLESv1_CM GLESv2 dl m`. El flujo UNIX (X11/ALSA) queda
    en el `else` para no romper el build de escritorio.
11. Steam: `USE_STEAM` OFF → `steam/steam.cpp` compila como stub vacío.

## Estado por fase

* **F1 — compila: VERDE** — run [40406490669](https://github.com/Hatblack2002/caesaria-game/actions/runs/40406490669)
  (commit 85a69d2, job `build` success). APK release firmado 142 MB:
  `lib/arm64-v8a/libmain.so` (11.4 MB) + 833 assets de `bin/resources` +
  clases Java (LauncherActivity/CaesarIA/CrashReporter/SDLActivity) + fuente
  Cinzel + iconos. Publicado como release `android-fase1`
  (caesaria-android-fase1.apk). Iteraciones de CI:
  1. 45961aa — REPO_ROOT con un `../` de más → configure_file/add_subdirectory
     fallaban. Corregido a 5 niveles.
  2. b5e64c5 — enlazado: `SDL_PrivateJoystick*` sin definir (backend joystick
     compilado con joystick deshabilitado) y `AndroidGestures::*` sin definir
     (GAME_PLATFORM_ANDROID no definido en androidgesture.cpp) → añadir
     `#include "core/platform.hpp"` y quitar el backend joystick.
  3. deb5612 — `Android_Window` no declarado (es interno de
     SDL_androidvideo.h) → declaración extern propia.
  4. b6040b0 — `Android_OnPadDown/OnJoy/...` sin definir: el JNI de SDL
     (core/android/SDL_android.c) referencia el backend joystick → se habilita
     joystick+gamecontroller (src/joystick/*.c + android) con
     remove_definitions(-DSDL_JOYSTICK_DISABLED) en la rama ANDROID.
  5. 85a69d2 — **verde**.
* **F2 — instala**: APK directo (no AAB), `extractNativeLibs` legacy packaging,
  firma PKCS12 incluida (`caesaria.keystore`, alias `caesaria`, pass
  `caesaria` — keystore de desarrollo, documentada a propósito).
* **F3 — arranca**: motor como libmain.so; splash/logo → menú del motor o
  acción directa del launcher; crash → reporte automático a GitHub.
* **F4 — recursos**: assets → `<ext>/resources` automático; mensaje + SAF si
  faltan datos; funciona sin datos de Caesar III originales.
* **F5 — táctil**: ver tabla de gestos; botones grandes ≥48 dp; UI del
  launcher en español con fuente Cinzel (OFL) incluida en `assets/fonts`.
* **F6 — estabilidad**: pendiente de pruebas en dispositivo (S20).

## Pendiente / conocidos

* `Opciones` abre el Lobby del motor (las opciones del motor son JS dentro
  del Lobby); una pantalla nativa de idioma/volumen queda como mejora.
* Música del splash: sin archivo libre de derechos incluido aún.
* Teclado virtual en cuadros de texto del motor (pendiente `SDL_StartTextInput`).
* El `data_path` extra se pasa al Intent pero el motor sigue usando
  `SDL_AndroidGetExternalStoragePath()` (mismo directorio); `-R` queda
  disponible por si se cambia.
