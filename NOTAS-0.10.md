# MusicFlow 0.10.0 — interfaz y preparación de audio

El usuario identifica la demora en «Preparar audio». Es evidencia de la etapa afectada, no un diagnóstico de red, servidor o decodificador. No se volvió a analizar Spotify ni se copió código de esa aplicación. La revisión previa de su APK enumera componentes de audio nativo, pero no permite conocer su política de red ni reproducir su infraestructura.

## Cambios de reproducción

- MediaPlayer se reemplaza por AndroidX Media3 ExoPlayer 1.6.1. MusicPlayer adapta sus eventos al servicio existente; el servicio conserva foco de audio, sesión multimedia, notificación y reproducción con pantalla bloqueada.
- Reserva inicial: 750 ms de audio; después de una interrupción: 2 segundos. Reserva objetivo de carga: 15–30 segundos. Estos valores no predicen la latencia total del proveedor.
- Conexión y lectura HTTP: 8 segundos sin respuesta por operación. Se desactivan los reintentos internos generales para que el servicio gestione un único nuevo intento con enlace renovado. La preparación por intento tiene un límite de 12 segundos; el límite global previo de 35 segundos incluye conseguir el enlace y ambos intentos. No se confunde una reducción de espera máxima con una reproducción exitosa más rápida.
- La siguiente canción se prepara solo después de acumular 5 segundos de audio de la actual. Se conserva un máximo de un candidato siguiente, un candidato de búsqueda cuando no hay sesión y el reproductor anterior durante la sesión.
- La UI muestra «Recibiendo audio» durante una interrupción. Pausar sigue funcionando mientras el motor rellena el búfer. Los enlaces, descargas y búsquedas siguen usando el proveedor anterior; cambiar de motor no elimina sus límites.

## Interfaz

Navegación inferior fija, mini reproductor encima de la navegación, barra fina de progreso, reproductor completo ampliable, listas compactas con carátula y menú de opciones, encabezados de colección con cantidad de canciones. Descargas deja de llamar «listas» a las canciones. Se conservan historial, favoritos, descargas, playlists y aleatorio.

## Revisión y entrega

Se revisaron fuentes y dependencias, se compiló Java/recursos/DEX y se verificó la firma del APK. Por petición del usuario, no se ejecutaron pruebas automáticas, emulador ni reproducción. No hay mediciones de arranque de 0.10 ni afirmación de que el problema esté resuelto en el celular.

APK manual: Android 13+, mismo identificador y clave de depuración anterior. Instalar encima sin desinstalar. Gradle declara Android 8+ y desugaring, pero esa compilación no se validó. Se incluyen los AAR y JAR originales, metadatos y fuentes disponibles en third-party/media3. Las dependencias de Media3 se incorporan también al compilador manual con sus recursos y clases R.

Referencias oficiales:
- https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl.Builder
- https://github.com/androidx/media/tree/1.6.1
- https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.6.1/media3-exoplayer-1.6.1.pom
