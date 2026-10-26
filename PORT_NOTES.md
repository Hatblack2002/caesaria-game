# PORT_NOTES — CaesarIA → Android (arm64)

Diario del port de CaesarIA (remake de Caesar III, C++/SDL2) a Android moderno.
Criterio de éxito: APK instalable en un Samsung Galaxy S20 que llegue al menú,
cargue una ciudad y se juegue con controles táctiles.

## Estado por fases

| Fase | Estado | Evidencia |
|------|--------|-----------|
| 1. Compila | ✅ local (NDK r27c + CMake, arm64-v8a) / 🔄 CI | `libmain.so` enlazado con éxito (ronda 12); workflow `.github/workflows/android.yml` |
| 2. Instala | 🔄 pendiente de probar en dispositivo | APK firmado con `android/app/caesaria.keystore` (alias `caesaria`, pass `caesaria`) |
| 3. Arranca | 🔄 pendiente | esperando logcat del dispositivo |
| 4. Recursos | ✅ diseñado | assets `bin/resources` → `getExternalFilesDir/resources` (primera ejecución) |
| 5. Táctil | ⬜ | SDL2 sintetiza ratón; ajustes de gestos pendientes |
| 6. Estabilidad | ⬜ | — |

## Arquitectura del port

- **Nada de SDL 1.2**: `master` ya usa `dep/sdl2mini` (fork de SDL 2.0.4) con
  backends Android completos (`src/video/android`, `src/audio/android`,
  `src/core/android`, `src/filesystem/android`...). La config por plataforma la
  resuelve `SDL_config_android.h` vía `__ANDROID__`.
- **Build**: Gradle 8.9 + AGP 8.5.2 + `externalNativeBuild` CMake 3.22.1 +
  NDK r27c. ABI única `arm64-v8a`. `minSdk 24`, `targetSdk/compileSdk 34`.
- **Librería nativa**: `libmain.so` (todas las dependencias estáticas dentro:
  sdl2mini, mixer+tremor, ttf, freetype, picoc, smk, aes, bzip2, lzma, minizip,
  png, zlib, litehtml). Punto de entrada JNI: `SDL_android_main.c` de sdl2mini
  → `Java_org_libsdl_app_SDLActivity_nativeInit` → `SDL_main` (`source/main.cpp`).
- **Java**: `org.libsdl.app.SDLActivity` (del repo, compatible con el fork) +
  `net.dalerank.caesaria.CaesarIA` que carga solo `main` (SDL2 es estático).
- **Render**: el juego usa `SdlEngine` (SDL_Renderer acelerado → GLES2 en
  Android). El `gl_engine` GLES1 existe pero no se instancia.
- **Recursos**: el APK incluye `bin/resources` (146 MB: modelos, gfx cache,
  audio, fuentes, maps, locale) como assets; `CaesarIA.onCreate` los extrae a
  `getExternalFilesDir(null)/resources` (ruta que lee el juego vía
  `SDL_AndroidGetExternalStoragePath()`), con marcador `.caesaria_assets_rev`.
  Los gráficos originales de Caesar III (`.sg2/.555`) los aporta el usuario en
  esa misma carpeta; el juego funciona sin ellos con el gfx cache propio.
- **Telemetría**: `CrashReporter.java` — al arrancar vuelca el ring buffer de
  logcat (sesión anterior, incluye tombstones nativos) a
  `files/telemetry/previous_session.log`, y sube crashes a Issues de un repo de
  logs usando token de `assets/telemetry.properties` (no commiteado; se
  inyecta desde secrets de CI). Sin token: modo offline, los informes quedan
  en el dispositivo.

## Problemas resueltos (con sus parches)

1. **`cmake_minimum_required(2.6)`** raíz → solo se usa en builds de escritorio;
   el build Android tiene su propio CMakeLists (mín. 3.14).
2. **sdl2mini sin rama Android en CMake** (`if(UNIX AND NOT APPLE)` tomaba el
   camino X11/ALSA) → añadida rama `if(ANDROID)` en
   `dep/sdl2mini/CMakeLists.txt` con las fuentes del backend Android
   (espejo del `Android.mk` histórico) y link a `log android GLESv1_CM GLESv2 dl`.
3. **zlib antiguo: `lseek` implícito** (clang moderno lo eleva a error) →
   `dep/zlib/gzguts.h` incluye `<unistd.h>` en no-Windows.
4. **`SDL_egl.c`: `ANativeWindow_setBuffersGeometry` no declarado** →
   `#include <android/native_window.h>` bajo `__ANDROID__`.
5. **`dep/aes/sha2.h`: `typedef int64_t sha2_64t` (con signo)** → los literales
   de tabla >2^63 no narrow en C++11 → `uint64_t`.
6. **mixer/tremor: punteros de función incompatibles** (warning-error en clang
   nuevo) → `-Wno-error=incompatible-function-pointer-types` solo para mixer.
7. **`dep/ttf/SDL_ttf.c:1729`: `return NULL` en función int** → `return 0`.
8. **`osystem.hpp`: enum con `unix`/`linux`** choca con macros predefinidas de
   clang → `#undef` guardados.
9. **`picture_bank.cpp` / `house.cpp`: comparación puntero > 0** (error C++11)
   → `!= nullptr`.
10. **CMake raíz definía `FREETYPELIB_NAME`** antes de añadir freetype; mi
    build Android no lo hacía → target vacío → `-lfreetype` no encontrado →
    fijado en el CMakeLists Android.
11. **`file(GLOB city/*.cpp)` faltante** en mi primer CMakeLists Android →
    símbolos `PlayerCity::*` sin definir → añadido módulo `city`.
12. **libpng vendido sin `arm/` pero con `PNG_ARM_NEON_OPT=2`** →
    `png_init_filter_functions_neon` sin definir → `-DPNG_ARM_NEON_OPT=0`.
13. **steamapi sin definiciones** → `steam/steam.cpp` (stubs con
    `GAME_USE_STEAM` off) añadido a las fuentes.

## Decisiones

- Se descarta el flujo antiguo (`ant`, NDK r9b, `armeabi`, Commander Genius,
  SDL 1.2 sobre pelya): reemplazo completo según la especificación.
- Keystore de release commiteado (clave de reparto desechable, solo
  sideloading): el APK debe poder firmarse en CI sin secretos externos.
- `extractNativeLibs=true` y `useLegacyPackaging` para máxima compatibilidad de
  carga de `.so` en el primer arranque.
- Sin curl/SDL_net/Steam en Android: no se usan desde `source/`.

## Pendientes / riesgos

- SDL 2.0.4 en Android moderno: probar orientación, teclado virtual
  (`SDL_StartTextInput`) y síntesis de ratón de touch (el fork incluye
  `SDL_androidmouse.c`).
- Audio: SDL 2.0.4 usa AudioTrack vía Java (`SDLActivity`); probar latencia.
- El primer arranque copia 146 MB de assets: puede tardar ~30 s; los siguientes
  arranques se saltan la copia por el marcador de revisión.
- Telemetría con token: crear repo privado de logs y secret
  `CAESARIA_TELEMETRY_TOKEN` en el fork para activar subida automática.
