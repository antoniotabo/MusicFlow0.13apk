# MusicFlow 0.13.0 — componentes abiertos

MusicFlow se distribuye bajo GPL-3.0-or-later; texto completo en LICENSE. Las modificaciones de MusicFlow de esta versión fueron realizadas el 18 de octubre de 2026. No es una aplicación oficial de Spotify, YouTube ni NewPipe.

| Componente | Versión | Licencia | Fuente |
|---|---|---|---|
| NewPipeExtractor | v0.26.5 | GPL-3.0-or-later | https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5 |
| nanojson | e9d656ddb49a412a5a0a5d5ef20ca7ef09549996 | Apache-2.0, Copyright 2011 The nanojson Authors | https://github.com/TeamNewPipe/nanojson |
| jsoup | 1.22.2 | MIT, Jonathan Hedley | https://github.com/jhy/jsoup |
| jsr305 | 3.0.2 | Apache-2.0 | https://central.sonatype.com/artifact/com.google.code.findbugs/jsr305/3.0.2 |
| protobuf-javalite | 4.35.1 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf/tree/v35.1 |
| Rhino y Rhino Engine | 1.8.1 | MPL-2.0 | https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release |

Los JAR binarios originales están en app/libs. Sus fuentes publicadas sin modificar están en third-party/sources y sus metadatos de publicación en third-party/*.pom. Los textos de licencia se incluyen en third-party/licenses y dentro de la app. Conserva avisos y fuentes al redistribuir. No se incluye código de Brave: un navegador completo no es necesario para este reproductor.

El proyecto Gradle usa además desugar_jdk_libs_nio 2.1.5 (Google/OpenJDK; licencias y excepciones incluidas en su distribución), para compatibilidad con versiones antiguas de Android. El APK de plataforma entregado no incluye esa biblioteca y requiere Android 13 o posterior.

## Reproductor 0.10

AndroidX Media3 1.6.1 (common, container, datasource, database, decoder, extractor y exoplayer), Apache-2.0, Android Open Source Project: https://github.com/androidx/media/tree/1.6.1 .

Dependencias: AndroidX annotation/annotation-jvm 1.6.0, annotation-experimental 1.3.1, collection 1.2.0, core 1.8.0, concurrent-futures 1.0.0, lifecycle-common/runtime 2.3.1, arch.core common/runtime 2.1.0, versionedparcelable 1.1.1, exifinterface 1.3.6 (Apache-2.0, Android Open Source Project); Guava 33.3.1-android, failureaccess 1.0.2 y listenablefuture 9999.0-empty-to-avoid-conflict-with-guava (Apache-2.0, Google); Kotlin stdlib/common 1.8.0 y JetBrains annotations 13.0 (Apache-2.0, JetBrains).

Binarios sin modificar en app/player-libs; POM, fuentes publicadas disponibles y avisos incluidos por los autores en third-party/media3. Las versiones Kotlin se unifican en 1.8.0 para satisfacer annotation 1.6.0. El POM de Kotlin 1.7.10 conserva la solicitud transitiva original. Las anotaciones opcionales de compilación de Guava no se empaquetan; jsr305 ya está en app/libs.
