# Rendimiento de las operaciones masivas

Qué se midió, qué estaba mal y qué se cambió para que seleccionar, respaldar y eliminar cientos de fotos no congele la
galería. Todas las cifras salen de pruebas instrumentadas que se pueden repetir (ver «Cómo repetir las mediciones»).

## Cómo se midió

- **Dispositivo:** emulador Android 17 (API 37, x86_64, 4 núcleos, 3 GB), compilación *debug*, fotos reales en MediaStore
  (JPEG de 1200×900) y un S3 simulado en `localhost`. Un emulador en un PC es bastante más rápido que un móvil medio: los
  valores **absolutos** son optimistas, lo que cuenta es la comparación antes/después en la misma máquina.
- **Antes:** el código de `HEAD` (`b6cf1b0`) en un *worktree* limpio, con las mismas pruebas. **Después:** la rama de trabajo.
- **Tiempo de hilo de UI por fotograma** (`FrameMetrics`: entrada + animación + medición/layout + registro de dibujo). Se
  excluye el hilo de render, porque el emulador rasteriza por software y solo añadiría ruido.
- **Latencia del hilo principal:** un hilo auxiliar publica un mensaje cada 8 ms y mide cuánto tarda en ejecutarse. Con el
  *harness* de Compose (que sincroniza con el hilo principal tras cada acción) esta cifra sale inflada en las fases de
  «tocar»; solo es fiable en las fases en que el *harness* está quieto (p. ej. tras pulsar «Permitir»).
- Google Fotos se desactivó en el emulador: reacciona a cada inserción en MediaStore y añade ruido de CPU.

## Qué estaba mal (con evidencia)

| # | Causa raíz | Evidencia (antes) |
|---|------------|-------------------|
| 1 | Tras el diálogo del sistema, `settle` comprobaba **un archivo cada vez** con una consulta a MediaProvider (IPC) | 300 fotos: **2 101 ms** (7 ms por foto), con un diálogo modal «Actualizando…» sin progreso |
| 2 | `MediaStore.createDeleteRequest` (llamada síncrona a MediaProvider) se ejecutaba **en el hilo principal** | 22 ms con 30 URIs, 43 con 100, **197 ms con 300** (crece más que lineal; en un móvil es varias veces más) |
| 3 | «Respaldar» y «Restaurar» hacían **una escritura por foto** | 300 fotos: **1 348 ms** y las listas de la pantalla se volvieron a consultar **295 veces** |
| 4 | `MAX_DELETE_BATCH = 100` (S3 admite 1 000) y una segunda petición de miniaturas por cada lote, todo en serie | 300 objetos: 6 peticiones; 2 500 objetos: **50 peticiones, 6 480 ms** |
| 5 | Cada vez que la app volvía al primer plano se **releía y comparaba toda la biblioteca** (`getEverything()` con todas las columnas) | 20 000 fotos: **816 ms y 13,7 MB** asignados en cada reanudación (también al volver del diálogo de borrado) |
| 6 | La barra de selección de S3 volvía a **leer y mapear todas las filas seleccionadas** tras cada toque | O(n) lecturas por toque, O(n²) al seleccionar n fotos |
| 7 | Consultas que se reejecutan tras *cualquier* escritura en la tabla y no se usan en la pantalla: lista de álbumes (joins), carpetas (escaneo completo con `GROUP BY`) | Se ejecutaban continuamente mientras había un respaldo o una cola grande |
| 8 | `PagedWindow.invalidate()` releía **todas** las páginas cargadas (hasta 20 × 96 filas) y `LibraryBrowser` reconstruía el *layout* aunque solo cambiase una insignia | Cada transición de estado de un respaldo provocaba una relectura masiva y recomposición de toda la rejilla |
| 9 | El borrado en S3 en lote bloqueaba la pantalla con un diálogo modal y se podía lanzar otro desde otra pestaña | `request()` ignoraba en silencio la segunda petición; sin exclusión entre pantallas |
| 10 | Miniaturas de la rejilla: Coil decodifica el original (JPEG de 12 MP a 350 px, en frío: mediana de **74 ms** en la línea base y 29–35 ms en las otras dos ejecuciones: el emulador varía) | La caché de miniaturas de la plataforma da el mismo tamaño en **5–9 ms** en las tres |
| 11 | `StrictMode` (compilación *debug*): el hilo principal descifraba las credenciales con el Keystore cada 30 s (`refreshStatus`) y, tras borrar en S3, borraba ficheros de la caché de previews | Violaciones de disco de 50–136 ms por comprobación; escrituras de disco en el hilo principal al borrar |

Lo que **no** era el problema: la recomposición de la rejilla al seleccionar. Con 1, 30, 100 y 300 fotos seleccionadas el
tiempo de hilo de UI por toque se mantiene plano (mediana 1–2 ms, p90 ≈ 5–10 ms); no crece con el tamaño de la selección. Tampoco lo era Firebase App Distribution: no interviene en tiempo de ejecución (solo distribuye
el APK); la diferencia entre *debug* y *release* se descarta con la compilación `benchmark` (ver abajo).

## Qué se cambió

**Borrado en el dispositivo** (`DeleteViewModel`, `MediaStoreGateway`, `LocalDeletionController`)
- La solicitud del sistema se crea en `Dispatchers.IO` y se muestra «Preparando…».
- `settle` comprueba la existencia con **una consulta `_id IN (…)` por colección y 400 ids**, no una por archivo; si no
  puede saber si un archivo existe, lo da por existente (nunca olvida un archivo por error).
- Las consultas a Room se trocean a 400 variables (SQLite de Android 10/11 admite 999).

**Borrado en S3** (`CloudDeletionController`, `S3RemoteStorage`)
- `DeleteObjects` con **1 000 claves por petición**; las miniaturas huérfanas se borran con el mismo tamaño de lote.
- Reintentos con espera creciente (500 ms, 1 s) para lo que S3 marca como transitorio (`SlowDown`, red, error interno),
  tanto de la petición entera como de claves sueltas; los permisos, credenciales y cuotas **nunca** se reintentan.
  Lo que sigue fallando se informa como fallido, jamás como eliminado.
- Las filas de Room se actualizan en cuanto S3 confirma, **aunque el usuario cancele justo entonces**; no hay
  transacciones abiertas durante una petición de red.
- Ejecuciones en serie (`Mutex`) y una **puerta global** (`AppContainer.deleteGate`): dos borrados masivos no se
  solapan, aunque se lancen desde pantallas distintas; el segundo recibe un aviso claro.

**Cola de respaldo / restauración** (`BackupController`, `RemoteLibraryController`, DAOs)
- Un `UPDATE … WHERE mediaStoreId IN (…)` por 400 fotos dentro de una transacción: una sola invalidación de Room.

**Biblioteca y listas** (`MediaIndexer`, `LibraryRepository`, `MediaChangeTracker`, `LibraryBrowser`, `PagedWindow`)
- `reconcile` lee una **proyección fina** (las columnas que se comparan) y solo carga filas completas de lo que cambió.
- Volver a la app **no reescanea** si MediaStore no cambió (contador `ContentObserver`) ni el permiso; «tirar para
  actualizar» y los *workers* siguen forzando el escaneo.
- Un cambio que no mueve ninguna foto (una insignia de respaldo) refresca el contenido sin reconstruir el *layout*.
- `invalidate()` relee solo las páginas cercanas a lo visible (±2).
- La lista de álbumes y la de carpetas solo se consultan mientras su hoja está abierta.

**Selección** (`GalleryViewModel`, `RemoteViewModel`, `MediaCell`)
- El resumen de la barra («12 con copia en S3», «también en este dispositivo») sale de un `COUNT` en SQLite, con
  *debounce* de 60 ms para los toques rápidos, fuera del hilo principal. En S3 ya no se leen filas completas.
- Las entradas del borrado se construyen en `Dispatchers.IO`.
- Cada celda tiene **2 animaciones en lugar de 4 + `AnimatedVisibility`**, leídas en la fase de dibujo (no recomponen);
  la preferencia de «reducir movimiento» se lee una vez, no por celda. (Menos trabajo por celda al hacer *scroll*; no se
  midió una mejora en el toque, ver los resultados.)

**Imágenes**
- `MediaStoreThumbnailFetcher`: las celdas de la rejilla piden la miniatura a `ContentResolver.loadThumbnail` (caché de la
  plataforma, también para vídeos) y, si no puede, Coil usa su ruta normal. Solo para tamaños de rejilla (≤ 640 px).

**Progreso y mensajes (UI)**
- El progreso del borrado ya **no bloquea la pantalla**: es una tarjeta inferior con el recuento («Eliminando 45
  elementos de S3… · 20 de 45»), barra indeterminada hasta el primer avance real (nunca un porcentaje inventado) y
  «Cancelar» cuando es seguro. La rejilla sigue desplazándose y seleccionándose debajo.
- Un fallo parcial muestra un diálogo con lo que se borró, lo que falló y por qué, con «Reintentar» solo para lo fallido.

### Un fallo propio que cazaron las pruebas

La primera versión de «relee solo lo cercano» perdía una página que *estaba cargándose* cuando cambiaban los datos: el
resultado en vuelo se descartaba por ser antiguo y nadie lo volvía a pedir, así que la rejilla se quedaba con cuadros
grises. Lo detectó `PhotoManagementUiTest` (4 de 5 casos fallaban en el aislamiento). `PagedWindow.invalidate()` ahora
vuelve a pedir también las páginas en vuelo, con una prueba que lo fija. Por eso las mediciones «después» se repitieron
sobre la compilación final.

### Claves de la rejilla: decisión

`LazyVerticalGrid` usa la **posición** como clave. Se evaluó usar el id de la foto, y se descartó: las páginas de
`PagedWindow` se cargan por separado, de modo que justo después de borrar fotos dos páginas pueden contener la misma foto
durante unos milisegundos, y una clave repetida en `LazyGrid` **cierra la app**. La posición es única por construcción.
El coste (que un hueco reutilice el estado de la celda vecina) es menor que el riesgo.

## Resultados (antes → después)

| Operación | Antes | Después |
|-----------|-------|---------|
| Poner en cola 300 fotos para respaldar | 1 348 ms, 295 relecturas de listas | **5 ms, 1 relectura** |
| Confirmar en el índice 300 archivos borrados | 2 101 ms | **29 ms** |
| `createDeleteRequest` de 300 URIs | 197 ms **en el hilo principal** | fuera del hilo principal |
| Borrar 300 objetos de S3 (peticiones) | 6 | **2** (1 de fotos + 1 de miniaturas) |
| Borrar 2 500 objetos de S3 | 50 peticiones, 6 480 ms | **6 peticiones, 2 155 ms** |
| Reanudar con 20 000 fotos (sin cambios) | 816 ms, 13,7 MB | **298 ms, 8,7 MB**, y sin escaneo si MediaStore no cambió |
| Miniatura 12 MP → 350 px (en frío) | 29–74 ms (Coil, según la ejecución) | 5–9 ms (caché de la plataforma) |

Borrado de extremo a extremo en el dispositivo (búsqueda → «Seleccionar todo» → «Permitir» → biblioteca actualizada), compilación final:

| Fotos | De «Permitir» a terminado | Peor fotograma tras «Permitir» (hilo de UI) | Latencia máx. del hilo principal tras «Permitir» |
|-------|---------------------------|---------------------------------------------|--------------------------------------------------|
| 30 | 1 553 → **200 ms** | 71,0 → **3,6 ms** | 192 → **83 ms** |
| 100 | 1 971 → **391 ms** | 100,2 → **53,0 ms** | 317 → **196 ms** |
| 300 | 2 968 → **391 ms** | 69,2 → **25,2 ms** | 311 → **90 ms** |

Al preparar la solicitud del sistema con 300 fotos el peor fotograma pasa de **127,3 ms a 20,1 ms** (la llamada a
MediaProvider ya no ocurre en el hilo principal). Quedan fotogramas sueltos por encima de 16,7 ms (p. ej. uno de 53 ms con
100 fotos justo después de «Permitir», cuando la rejilla se recompone con las filas ya eliminadas): el borrado no bloquea
la UI, pero tampoco está libre de saltos puntuales.

Memoria durante un borrado de 300 fotos: montón de Java 16,7 → 18,2 MiB, PSS 189,8 → 190,7 MiB, 1 GC, 9 MiB asignados.

### Selección de 1, 30, 100 y 300 fotos (toques reales sobre ~340 fotos de MediaStore)

Tiempo de hilo de UI por fotograma mientras se toca (mediana / p90 / máximo, y fotogramas por encima de 16,7 ms):

| Seleccionadas | Antes | Después |
|---------------|-------|---------|
| 30 | 1,8 / 9,6 / 16,1 ms · 0 de 351 | 2,2 / 8,4 / 16,9 ms · 1 de 330 |
| 100 | 1,3 / 5,9 / 28,6 ms · 1 de 982 | 1,5 / 6,5 / 16,7 ms · 0 de 1 076 |
| 300 | 1,1 / 4,6 / 22,6 ms · 1 de 2 929 | 1,3 / 5,7 / 21,9 ms · 4 de 3 329 |

**Lectura honesta:** la selección no era el cuello de botella y sigue igual dentro del ruido de medida. El coste por toque es
plano respecto al tamaño de la selección (p90 ≈ 5 ms con 300 fotos seleccionadas) y desplazarse con 300 seleccionadas es
igual de fluido (p90 12,0 → 7,8 ms). La memoria durante la selección (≈ 500 MiB asignados y ≈ 60 GC al recorrer 300 fotos
con miniaturas: 494 → 537 MiB, 64 → 62 GC) no cambió. Lo que sí se corrigió en esta ruta es trabajo que escalaba con la selección *fuera* de la
recomposición: la barra de la pestaña S3 releía todas las filas seleccionadas tras cada toque (O(n²)) y la lista
de álbumes / carpetas se reconsultaba tras cada escritura; ver «Qué se cambió».


## ¿Debug o app? Compilación tipo *release*

Las mismas pruebas contra la compilación `benchmark` (código de *release*, no depurable, sin herramientas de depuración):

| Medida | Debug | Tipo *release* |
|--------|-------|----------------|
| Borrado de 300: de «Permitir» a terminado | 391 ms | **171 ms** |
| Borrado de 300: peor fotograma (preparar la solicitud / tras «Permitir») | 20,1 / 25,2 ms | **16,4 / 15,9 ms** (ninguno > 16,7) |
| Cola de respaldo de 300 fotos | 6 ms | 4 ms |
| Reanudar con 20 000 fotos (sin cambios) | 292 ms | **104 ms** |
| Confirmar 300 borrados en el índice | 29 ms | 9 ms |
| Borrar 2 500 objetos de S3 | 2 152 ms | 1 790 ms |
| Memoria (PSS) durante el borrado de 300 | ≈ 190 MiB | **≈ 103 MiB** |

La compilación *debug* multiplica por 2–3 el coste del código de la app y casi duplica la memoria, pero las mejoras
se ven igual en las dos. Firebase App Distribution no interviene en tiempo de ejecución (solo reparte el APK), así que no
es una causa posible de lo observado.

## Pruebas

- **Unitarias (JVM/Robolectric):** 360 pruebas, todas en verde, incluidas las nuevas: lotes de 1 000, reintento de claves limitadas,
  reintento de red, que los permisos no se reintentan, cancelación justo después de la respuesta de S3, exclusión mutua,
  existencia en lote, escaneo omitido sin cambios, equivalencia de la proyección fina de `reconcile` y
  `invalidate()` local de `PagedWindow`.
- **Instrumentadas (emulador):** 58 pruebas en verde (las de la app: borrado, S3, galería inteligente, álbumes, coste,
  respaldo/restauración, migración, más las 3 nuevas de `BulkCloudDeleteUiTest`) y los *benchmarks* `BulkOperationsBenchTest`,
  `BulkDeletePerfTest` y `BulkSelectionPerfTest`.
- Se comprobó también que `assembleRelease` (con R8) sigue compilando.

## Cómo repetir las mediciones

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.s3vault.app.BulkOperationsBenchTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.s3vault.app.BulkDeletePerfTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.s3vault.app.BulkSelectionPerfTest
adb logcat -d -s BulkPerf
```

`-PperfBuildType=benchmark` ejecuta las mismas pruebas contra una compilación tipo *release* (no depurable, sin
herramientas de depuración, firmada con la clave de depuración) para separar el coste de la app del de la compilación
*debug*. En compilaciones *debug* `StrictMode` registra (sin cerrar la app) el trabajo de disco o red en el hilo principal.

## Lo que no se pudo verificar

- Las cifras son de un emulador; en un móvil real los valores absolutos serán mayores (los **ratios** deberían mantenerse).
- No se midió contra un bucket real de AWS: el S3 simulado cubre el protocolo y el SDK real, no la latencia ni el
  *throttling* reales.
- Miniaturas de HEIC/AVIF, RAW y vídeos largos en dispositivos reales (dependen del códec del fabricante).
- Bibliotecas de decenas de miles de fotos *reales* en MediaStore (se midió el índice con 20 000 filas sintéticas y la
  rejilla con ~340 fotos reales).
