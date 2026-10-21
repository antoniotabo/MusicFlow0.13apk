# MusicFlow 0.9 — revisión de la espera de reproducción

16 de octubre de 2026. Se revisó el código y se compiló el APK. Por petición del usuario no se ejecutaron pruebas, MusicFlow, Spotify ni un emulador. No hay una medición del rendimiento de esta versión en el teléfono.

## Problemas encontrados en MusicFlow

La versión 0.8 compartía un executor de extracción de dos trabajadores entre reproducción seleccionada, preparación anticipada y descargas. Solicitudes ajenas podían mantener ocupados ambos trabajadores. Cancelar la espera del servicio no cancelaba la tarea compartida de extracción: las selecciones abandonadas podían seguir ocupando la cola y usando conexiones hasta finalizar. Son riesgos identificados por lectura del código, no una medición de la causa específica de los diez segundos reportados.

Cada nuevo toque en la canción también enviaba PLAY, reconstruía la cola y descartaba el reproductor actual, incluso cuando esa misma canción ya estaba cargando. La cadena de resolución, preparación y reintento tenía plazos individuales largos sin un presupuesto común de arranque.

## Cambios

- Dos trabajadores reservados a resolución de reproducción seleccionada y uno para preparación/descargas. Si se selecciona una canción con trabajo ya en cola de segundo plano, se promueve a la cola de reproducción; si ya está trabajando, se comparte la misma solicitud.
- Recuento de consumidores de cada resolución. Cuando ya no queda ninguno, se cancela el trabajo, se retira de las colas y se desconectan sus conexiones HTTP. Hay una ventana de 750 ms para transferir la preparación anticipada al reproductor seleccionado sin descartarla.
- Los toques repetidos sobre la misma canción en carga ya no reinician el proceso. Una canción en pausa se reanuda.
- Plazo global de arranque de 35 segundos que incluye el reintento. Al agotarse se libera la carga y aparece un mensaje para reintentar o elegir otra canción. Es una recuperación ante bloqueo, no una mejora de velocidad por sí misma; el temporizador depende del bucle de Android.
- Contador de espera y detalles locales de resolución/preparación/total. No se guardan ni transmiten diagnósticos, enlaces temporales ni datos de cuenta. Estos tiempos miden etapas de la app, no el instante acústico del altavoz. Con precarga se mide la espera restante después de seleccionar; en un reintento las etapas corresponden al último intento y el total incluye los dos.

Las descargas, las playlists, el modo aleatorio y la reproducción en segundo plano se conservan. Una descarga ya registrada se sigue reproduciendo directamente como archivo local. No se añadieron bibliotecas ni código de Spotify.

## Análisis estático del APK suministrado

REA/JADX leyó el APK sin instalarlo ni ejecutarlo. El archivo declara paquete com.spotify.music, versión 9.1.90.2270, código 147078674, minSdk 24 y targetSdk 37. Inventario: 98.076 clases y 4.874 recursos. Su firma y procedencia no fueron verificadas en esta revisión.

SHA-256 del APK: 5c05366de31788fd0eec6e046c3610311acf5e63c15345e5bdaca6e8f09fb3b4.

Se localizaron componentes com.spotify.playbacknative.AudioDriver, AudioTrackAdapterImpl, AudioRenderer, OboeRenderer y MediaCodecFileDecompressor. La inspección de miembros de AudioDriver muestra constructAudioTrack, getQueuedSize, getFreeSize, onBufferUnderrun y startPlayback, entre otros. Esto demuestra la presencia de interfaces de salida de audio y seguimiento de buffers; no establece sus algoritmos, configuración efectiva, uso en todas las rutas ni latencia real de red. No se copió código descompilado ni se modificó el APK.

El análisis del APK no demuestra cómo acelerar la resolución de enlaces de YouTube que utiliza MusicFlow. Cambiar la apariencia o trasladar nombres de clases no sustituye ese paso. La resolución inicial de una canción sin preparar sigue dependiendo del proveedor y la conexión. La nueva información de carga permite distinguir esa espera de la preparación del audio en el teléfono.

## Entrega

MusicFlow-0.9.apk requiere Android 13 o posterior. Instalar sobre la versión anterior sin desinstalar para conservar biblioteca y descargas. La firma de depuración se conserva. El proyecto fuente completo se entrega por separado; los tests históricos no se ejecutaron ni certifican 0.9.
