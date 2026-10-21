# MusicFlow 0.11.0

Cambios del 18 de octubre de 2026:

- Guarda resultados (hasta 500), texto de búsqueda, pestaña, playlist seleccionada, filtro de biblioteca y desplazamiento. No vuelve a ejecutar automáticamente la búsqueda al abrir. Para obtener nuevas páginas del proveedor se vuelve a buscar; sus tokens temporales no se guardan.
- Guarda cola, orden original, canción y posición del servicio cada dos segundos y al pausar/cerrar. Recupera la sesión pausada después de que Android termine el proceso. Si el servicio sigue reproduciendo en segundo plano, se muestra su estado real. En un cierre abrupto puede perderse el último intervalo de guardado. Una emisora en vivo no se rebobina al punto anterior.
- La × del reproductor pausa y vuelve a la lista, conservando el mini reproductor y la sesión. La × del buscador vacía únicamente el texto, sin borrar biblioteca ni historial.
- Aleatorio excluye los identificadores de canciones que hayan iniciado reproducción desde esta versión, incluso entre sesiones. Una selección manual o Anterior puede reproducirlas deliberadamente. No se puede recuperar un historial que versiones anteriores no guardaban. Distintas versiones de una canción con diferentes identificadores se consideran pistas diferentes.
- Al agotar candidatos, el botón Aleatorio informa que no quedan canciones nuevas. Configuración permite reiniciar explícitamente ese historial.
- El contenedor nativo aplica márgenes para barras del sistema, recorte de cámara y teclado; el WebView recibe un espacio ya delimitado. El encabezado suma separación visual.
- Motor Media3, resolución de enlaces y parámetros de búfer/tiempos de 0.10 conservados.

Revisión de fuentes, comprobación de sintaxis JavaScript, compilación de Java/recursos/DEX y verificación de firma. No se ejecutaron tests, emulador ni pruebas en celular por petición del usuario. La presentación en el dispositivo y la restauración real quedan pendientes de su comprobación.

APK manual para Android 13+, mismo paquete y clave de depuración. Instalar sobre la versión anterior sin desinstalar para conservar datos. El guardado de navegación y sesión empieza al usar 0.11.

Referencia de márgenes Android: https://developer.android.com/develop/ui/views/layout/edge-to-edge
