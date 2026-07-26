# Deep Bass Global (Android)

App con el mismo motor de graves que usamos en la extensión de navegador (grave tipo
"shelf" + "punch" + limitador anti-distorsión), pero aplicado a nivel de sistema:
afecta a **cualquier** app que esté reproduciendo audio (Spotify, YouTube Music,
podcasts, etc.), no solo a una.

## Cómo se logra el "control total"
Se usa `audioSessionId = 0`, que en Android representa el mix de audio de salida
global del dispositivo. Crear un efecto ahí (en vez de en la sesión de una sola
app) hace que el procesamiento se aplique al audio final, venga de donde venga.
Es la misma técnica que usan apps conocidas de bass boost / ecualizador del Play
Store — no requiere root.

- En Android 9 (API 28) en adelante se usa `DynamicsProcessing`, que permite
  configurar el shelf de graves, la banda de "punch" y el limitador con bastante
  precisión (el equivalente nativo de Android a la cadena de Web Audio que
  hicimos en el navegador).
- En versiones más antiguas, cae a un fallback con `BassBoost` + `Equalizer` +
  `LoudnessEnhancer`, más simple pero funcional.

## Cómo compilarlo
1. Abre la carpeta `GlobalBassBoost/` con Android Studio (Giraffe o más reciente).
2. Deja que sincronice Gradle (descargará las dependencias automáticamente).
3. Conecta tu teléfono (con depuración USB activada) o usa un emulador, y dale a
   "Run". Eso instala el APK directamente.
4. Si prefieres un `.apk` para compartir: `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
   El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

No pude compilarlo yo mismo a un `.apk` porque mi entorno no tiene el SDK de
Android instalado ni acceso a internet para descargarlo — pero el proyecto está
completo y debería compilar sin cambios.

## Limitaciones reales (no son bugs, son restricciones del sistema)
- **Fabricantes que bloquean efectos globales de terceros**: notablemente Samsung
  con su propio "Adapt Sound"/UI de sonido. En esos casos el efecto puede no
  sonar, sin que haya nada que el código pueda hacer al respecto sin root.
- **Audio "offloaded"**: algunas apps (Spotify, YouTube Music) a veces envían el
  audio comprimido directo al chip de audio para ahorrar batería en reproducción
  en background. Cuando eso pasa, los efectos de sesión no se aplican. Suele
  solucionarse solo mientras la pantalla de reproducción está activa/foreground.
- El servicio necesita permanecer como **servicio en primer plano** (con su
  notificación persistente) mientras el bass boost esté activo — es un requisito
  de Android para servicios de larga duración, no algo opcional en este diseño.

## Estructura
```
app/src/main/java/com/bassboost/global/
  MainActivity.kt        -> UI con el slider
  GlobalBassService.kt   -> motor de audio global (DynamicsProcessing / fallback)
app/src/main/res/layout/activity_main.xml
```
