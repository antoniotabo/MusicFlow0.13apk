# MusicFlow 0.13.0 — cerrar y conservar la sesión

La versión anterior mantenía el servicio de reproducción al retirar la tarea de recientes. Guardar la sesión no necesita mantener activo el reproductor.

- onTaskRemoved guarda la posición, cola e historial antes de liberar el motor, cancela búsqueda de continuación y precargas, detiene los trabajadores del servicio, abandona foco de audio, libera la sesión multimedia y retira la notificación. Termina el servicio sin solicitar su reinicio.
- La limpieza es idempotente: onDestroy no vuelve a guardar la posición después de liberar el motor ni sobrescribe la sesión con ceros. Se bloquean nuevos intentos de reproducir desde callbacks pendientes durante el cierre.
- El guardado final de la sesión se confirma en almacenamiento antes de terminar. El guardado periódico normal sigue siendo asíncrono.
- La × del reproductor cierra el servicio en lugar de dejarlo pausado diez minutos. El mini reproductor puede mostrar la sesión guardada en pausa mientras la app esté abierta; no representa un servicio activo.
- Se conserva la reproducción al usar Inicio, cambiar de aplicación o bloquear la pantalla. Retirar MusicFlow de recientes cierra la reproducción.
- Al volver a abrir, se recuperan pantalla/resultados y sesión guardada en pausa. La reproducción solo empieza al pulsar reproducir.
- El manifiesto mantiene stopWithTask=false para recibir onTaskRemoved y guardar explícitamente antes de stopSelf. No significa que la app siga reproduciendo después de cerrar.

Compilación Java/recursos/DEX, sintaxis JavaScript y firma del APK. No se ejecutaron tests, emulador ni pruebas de reproducción/cierre en dispositivo, según la preferencia del usuario. El comportamiento del gesto debe confirmarse en su teléfono. No se cambian extracción, búfer, icono ni continuación de canciones de 0.12.

APK Android 13+, mismo paquete y firma anterior. Instalar encima sin desinstalar.

Referencia oficial: https://developer.android.com/reference/android/app/Service#onTaskRemoved(android.content.Intent)
