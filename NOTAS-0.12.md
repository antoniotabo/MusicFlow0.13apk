# MusicFlow 0.12.0

- Icono adaptativo propio en Android: nota musical lavanda/menta sobre fondo oscuro. Se aplica al launcher y su variante redonda. Recursos vectoriales, sin dependencias nuevas.
- Continúa las colas online con bloques de hasta 20 canciones. Inicia la consulta cuando quedan como máximo tres canciones pendientes y la actual tiene al menos cinco segundos de audio almacenado. Al acabar la última canción, espera el bloque si sigue pendiente y continúa cuando llega, salvo que el usuario haya pausado.
- Consulta páginas de la búsqueda original usando el filtro music_songs; al agotarse esa consulta intenta con el artista/canal de la canción. Es continuación por búsqueda/artista, no un motor personalizado ni una garantía de similitud musical. La disponibilidad y la cantidad dependen del proveedor.
- Filtra IDs que ya están en cola o en el historial escuchado. En modo aleatorio mezcla solo el nuevo bloque. Conserva el orden existente. Distintas versiones con IDs distintos siguen siendo pistas distintas.
- Consultas en trabajador independiente de la reproducción, hasta cuatro páginas por bloque y 45 segundos de plazo. Si no hay novedades o falla la consulta, informa sin repetir canciones ni entrar en un bucle. Tope de 5000 canciones por cola. No cambia los tiempos ni el búfer del motor de 0.10.
- El servicio realiza la continuación, incluso con la pantalla bloqueada. Descargas y Mi música no activan continuación online al crear una cola.
- El reproductor muestra el estado y las últimas 100 canciones añadidas en «Canciones añadidas automáticamente»; permite seleccionarlas. La cola ampliada se guarda como en 0.11. Los tokens temporales del proveedor se mantienen solo en memoria; al reiniciar se recupera la cola, pero la siguiente consulta puede comenzar desde la primera página y filtra lo ya escuchado.
- Para explorar más resultados manualmente continúa disponible «Ver más canciones» al pie de la búsqueda cuando el proveedor entrega otra página.

Compilación y firma del APK, revisión de fuentes y sintaxis JavaScript. No se ejecutaron pruebas automáticas, emulador ni reproducción por petición del usuario. La continuación real, cantidad de resultados y apariencia del icono deben confirmarse en su teléfono. Instalar sobre 0.11 sin desinstalar. APK Android 13+.
