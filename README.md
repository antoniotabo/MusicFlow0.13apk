# MusicFlow 0.13.0

App Android de música con interfaz propia, búsqueda por nombre, favoritos, playlists, descargas y reproducción de audio en segundo plano. Se continúa el proyecto existente en este equipo; no depende de la PC averiada. No usa cuentas ni código de Spotify.

El cierre desde recientes y la × se documentan en [NOTAS-0.13.md](NOTAS-0.13.md). El icono y la continuación online se conservan de [0.12](NOTAS-0.12.md). Se conservan el guardado, aleatorio, buscador y márgenes de [0.11](NOTAS-0.11.md). Los ajustes de reproducción de 0.10 se conservan.

## Instalar

Instala MusicFlow-0.13.apk sobre la versión anterior, sin desinstalarla, para conservar biblioteca y descargas. El APK entregado requiere Android 13 o posterior y conserva la firma de depuración anterior.

## Funciones conservadas

- Solo búsqueda de canciones; se retiró el selector música/video.
- Interfaz oscura con acentos lavanda y menta, listas compactas, menú de opciones por canción, navegación inferior fija y reproductor ampliable. Botones de reproducción por canción y por colección.
- Aleatorio nativo: activa ⤨ en el reproductor o Aleatorio en una colección. Se mezcla la cola sin repetir sus elementos; al activarlo durante una canción se conserva la canción y el tramo ya recorrido. Desactivarlo devuelve el tramo pendiente al orden original. Funciona también con pantalla bloqueada. No es un modo de repetición infinita.
- Extracción enfocada en audio: no se solicitan al extractor los formatos de video, subtítulos, recomendaciones ni metadatos opcionales que no usa el reproductor.
- Respuestas de catálogo comprimidas con gzip cuando el proveedor lo admite.
- Se conserva un reproductor anticipado aunque todavía esté preparando el audio al seleccionarlo. Antes se descartaba si no había terminado, abriendo otra preparación.
- Hasta diez primeras páginas recientes de búsqueda guardadas localmente durante 24 horas, con hasta 60 canciones cada una. Se muestran al repetir una consulta y se actualizan en segundo plano. Los tokens de paginación no se guardan en disco. Borrar historial elimina también esta caché.
- Caché nativa de primeras páginas durante diez minutos, comprobando que el token de paginación aún exista.
- Consulta de progreso de descargas separada de los trabajadores del buscador.
- Estados de carga, pausa, reproducción online y archivos sin conexión más claros. La carga puede cancelarse desde el reproductor.

## Descargas y biblioteca

Descargas conserva archivos en el espacio de MusicFlow, los reproduce sin volver a pedir su enlace online y permite borrar o reintentar. Se eliminan al desinstalar. Los respaldos incluyen favoritos y playlists, no los archivos de audio ni sus permisos. Mi música permite importar archivos del teléfono y enlaces HTTPS de radio/audio.

## Rendimiento y alcance

Las optimizaciones reducen trabajo innecesario y reutilizan preparación. No hay una medición de velocidad de 0.10 ni una promesa de inicio instantáneo. La primera búsqueda y una canción sin preparar dependen de la conexión y del proveedor. La preparación anticipada consume datos adicionales; no es reproducción gapless. Los enlaces de audio con vencimiento solo se guardan en memoria. El proveedor puede cambiar o restringir contenidos.

No se ejecutaron pruebas automáticas, emulador ni pruebas en dispositivo en esta entrega 0.13, por petición del usuario. Solo se revisó el código y se compiló y firmó el APK. Las pruebas del directorio tests pertenecen a versiones anteriores y no certifican 0.13; algunas expectativas de interfaz deben actualizarse antes de volver a utilizarlas.

## Compilar

En Windows, con el JDK de Android Studio, Android SDK 35 y build-tools instalados:

    ./build-platform-apk.ps1 -Sdk 'RUTA/Android/Sdk'

Compila sin descargar dependencias, con los JAR incluidos en app/libs y las dependencias JAR/AAR de app/player-libs. Genera ../MusicFlow-debug.apk para Android 13+. Usa la clave de depuración existente; cambiar de firma impide actualizar la instalación anterior. El proyecto Gradle declara Android 8+ con desugaring, pero esa variante no está validada. No hay proyecto iOS todavía.

## Referencias y siguientes mejoras

Se consultaron los controles públicos de Spotify como referencia de organización visual, sin modificar esa aplicación ni copiar su código:
https://support.spotify.com/td-en/article/now-playing/

Se revisó el código fuente incluido de NewPipeExtractor 0.26.5 y su API:
https://teamnewpipe.github.io/NewPipeExtractor/javadoc/org/schabi/newpipe/extractor/stream/StreamInfo.html

Mejoras candidatas, todavía no implementadas: calidad de audio configurable para redes lentas; una pantalla para reordenar la cola; temporizador de apagado; repetir canción/colección. Esta versión cambia a Media3; quedan pendientes las mediciones en el celular de búsqueda, resolución del enlace y preparación. Una interfaz parecida a Spotify no cambia la velocidad del proveedor de audio.

## Licencias

GPL-3.0-or-later. Incluye fuentes de bibliotecas y avisos en third-party/, THIRD_PARTY.md y LICENSE. Distribuye el proyecto completo junto al APK. No hay servidor propio, analítica ni sincronización. Las consultas, portadas y audio conectan con YouTube. Descarga contenido que tengas derecho a guardar.
