# Pruebas

## Resultado de la última ejecución

> **Actualización del 26 de octubre de 2026 (función «Personas»).** Unitarias: **678 pruebas, 0 fallos** (119 son de Personas; repetidas el 27 de octubre tras añadir el interruptor `-PwithFaces`). Instrumentadas en el emulador (Android 17 / API 37, x86_64) con `am instrument`: **148 pruebas, 0 fallos** (131 anteriores + 17 nuevas). Lint: 0 errores, 54 avisos. Release con R8: compila (APK arm64 51,3 MiB; AAB 35,8 MiB) y la compilación minificada para x86_64 se comprobó en el emulador (análisis, grupo y hoja de regla). La tabla de abajo es la de la ronda anterior; el detalle de lo nuevo está en [«Personas»](#personas-agrupación-de-rostros-y-respaldo-por-persona).

| Suite | Comando | Resultado |
|---|---|---|
| Unitarias (JVM + Robolectric) | `./gradlew testDebugUnitTest` | **559 pruebas, 0 fallos** (dos pasadas seguidas; una pasada intermedia mostró el fallo intermitente de Windows descrito más abajo, que no se repitió) |
| Instrumentadas (emulador Android 17 / API 37, x86_64) | `adb shell am instrument -w com.s3vault.app.test/com.s3vault.app.S3VaultTestRunner` (ver nota) | **131 pruebas, 0 fallos** (una sola ejecución completa) |
| Lint | `./gradlew lintDebug` | 0 errores, 52 avisos (versiones, `UseKtx`, 4 de `android.media.ExifInterface`; ninguno de seguridad) |
| Build release con R8 | `./gradlew assembleRelease` | OK. **33,6 MiB** por defecto (solo arm64), 53,1 MiB con arm64 + armv7, 119 MiB con las cuatro ABI (ONNX Runtime pesa 19–33 MiB por ABI: ver [semantic-search.md](semantic-search.md#peso-del-apk)). Compilada para x86_64 y firmada con la clave de depuración, **arranca en el emulador, abre Álbumes privados y crea el vault con el PIN real** (sin fallos de R8) |

Nota sobre las instrumentadas: se lanzan con `am instrument` (no con la tarea `connected…` de Gradle, que desinstala la app y con ella el modelo de búsqueda copiado a mano al emulador). Dos suites **dependen del estado del dispositivo**: `SemanticModelDeviceTest` necesita el pack del modelo en el dispositivo (sin él se omite) y una biblioteca **sin cientos de fotos idénticas** (tres fotos iguales empatan en la similitud), y `SmartGalleryUiTest` ahora inserta sus propias fotos de relleno en el escenario que las necesita. Una ejecución anterior mostró un fallo suelto de `SmartAlbumsUiTest` (contador en vivo) que no se repitió en las ejecuciones siguientes ni al lanzarla sola; no se encontró la causa, así que se anota como **intermitente conocido**.

Todo lo anterior se ejecutó realmente; nada de lo que sigue se declara como pasado sin haberse ejecutado. Lo que **no** se ha podido ejecutar está en [Validación manual pendiente](#validación-manual-pendiente).

## Cobertura de los puntos pedidos

| Área | Dónde |
|---|---|
| Galería local | `IndexerAndControllersTest` (indexación, sin hash al indexar, reescaneo idempotente), `AppFlowTest` (navegación y estados) |
| Acceso a MediaStore | `BackupRestoreEndToEndTest` (inserta imágenes reales en MediaStore, las escanea con `MediaStoreSource`, las lee, las restaura) |
| Configuración de S3 | `ConfigValidatorTest`, `AppFlowTest` (región/bucket/prefijo inválidos, `..` en prefijo) |
| Credenciales estáticas | `CredentialsRepositoryTest`, `KeystoreSecretStoreTest` (Keystore real), `AppFlowTest.savingStaticCredentials…` |
| Temporales y expiración | `TemporaryCredentialsParserTest`, `CredentialsRepositoryTest` (aviso, caducidad, `ExpiringSoon`), `BackupEngineTest` y `BackupRestoreEndToEndTest` (la cola se conserva y se reanuda con credenciales nuevas) |
| Permisos insuficientes | `S3RemoteStorageTest` (403), `BackupEngineTest` (FAILED permanente y bloqueo de la tanda), e2e |
| Región incorrecta | `S3RemoteStorageTest` (301 + `x-amz-bucket-region`), `S3ErrorClassifierTest` |
| Subida | `BackupEngineTest`, `S3RemoteStorageTest` (cabecera SHA-256, cuerpo, metadatos, SigV4), e2e contra servidor S3 simulado |
| Restauración | `RestoreEngineTest`, e2e (bytes idénticos, segunda restauración **no** sobrescribe) |
| Duplicados | `BackupEngineTest`: contenido idéntico enlaza el objeto existente sin subir; mismo nombre ≠ colisión |
| Archivos modificados | `BackupEngineTest`: nueva versión, la anterior se conserva; cambio solo de fecha no re-sube; caché de hash |
| Integridad | `BackupEngineTest` (checksum de verificación distinto ⇒ no `BACKED_UP`), `RestoreEngineTest` (descarga corrupta nunca se publica) |
| Reintentos | `BackupEngineTest`: backoff por intentos, máximo 6, `FAILED` reintentable |
| Interrupción de red | `BackupEngineTest`, `RestoreEngineTest` (reanudación con `Range`), `S3RemoteStorageTest` (servidor caído ⇒ `NETWORK`) |
| Reinicio de la app | `BackupEngineTest`: filas congeladas en `UPLOADING/VERIFYING` se recuperan y completan con un motor nuevo; cancelación devuelve a `QUEUED` |
| Aislamiento del prefijo | `RemoteKeysTest`, `S3RemoteStorageTest` (claves fuera del prefijo se rechazan **sin enviar nada**), `BackupEngineTest` |
| Borrados locales no afectan a S3 | `IndexerAndControllersTest`, e2e (no se emite ningún `DELETE`) |
| Ausencia de secretos en logs | `RedactorTest`, `NoSecretsInLogsTest` (análisis estático: solo `SafeLog` usa `Log`; sin literales `AKIA…`), `BackupEngineTest.no secrets reach the stored error text`, `CredentialsRepositoryTest.status never exposes secret values`, `KeystoreSecretStoreTest` (el archivo no contiene el texto plano) |
| Hilo principal | `s3CallsFromTheMainThreadNeverTouchTheNetworkOnIt` (reproduce un bug real hallado en la build release) y `theNewS3OperationsAreSafeToCallFromTheMainThread` (`delete`, `readBytes`, `putThumbnail`) |
| Miniaturas en S3 | `PreviewUploadTest` (se sube con el respaldo; un fallo no rompe el respaldo y lo recupera el *backfill*; desactivable; sin credenciales no hace nada; nueva versión ⇒ nueva miniatura), `PreviewAndDeletionEndToEndTest` (miniatura JPEG real de ≤ 720 px, ≪ original, no aparece como foto) |
| Vista previa sin descargar | `RemotePreviewRepositoryTest` (orden miniatura → EXIF con `Range` → «no disponible»; caché y fallos cacheados con caducidad; errores transitorios no se cachean; `403` = sin miniatura; objeto borrado en otro sitio = «no existe»; presupuesto de caché), e2e: el original **no** se pide (`getKeys`), una lectura `Range` de 128 KiB lee una miniatura EXIF real |
| Borrado en S3 | `CloudDeletionTest` (objeto + miniatura, local intacto y marcado, no re-sube, miniatura compartida, fallos por clave y reintento, denegación IAM detiene los lotes, restauración en curso, claves no permitidas, cancelación entre lotes, progreso), `S3RemoteStorageTest` (`POST ?delete` real contra el SDK: XML, `Quiet`, errores por clave, rechazo sin enviar nada), e2e con `DeleteObjects` real sobre ART |
| Borrado local | `LocalDeletionTest` (lo que Android borró de verdad se asienta; cancelar no cambia nada; parcial; S3 intacta), e2e con MediaStore real, `PhotoManagementUiTest` con el **diálogo real del sistema** |
| Estimación de costes | `CostEstimatorTest` (tramos de Standard, GiB y no GB, mínimo de 128 KiB, sobrecarga de Glacier, Intelligent-Tiering, **sin total** con clase sin precio / región sin precios / inventario incompleto, vistas previas como suposición, niveles de confianza, solicitudes), `S3PricingTableTest` (cifras publicadas, las 36 regiones completas y con tramos coherentes), `StorageInventoryTest`, `InventoryCodecTest`, `CostFormatTest`, `CostPersistenceTest` (el listado que ya se hace alimenta el inventario; un fallo no lo guarda; DataStore; eventos de subida y borrado), `S3RemoteStorageTest` (clase de almacenamiento, número de solicitudes, fallo a mitad de listado) |
| Pantalla de costes | `StorageCostUiTest` (12 flujos sobre la app real: sin inventario, cálculo, bucket vacío, clase sin precio, región sin precios, fallo que conserva lo anterior, cambios pendientes, inventario antiguo, otro bucket, sin S3, sin credenciales, ayuda) |
| Migración de Room | `MigrationTest` (v1 → v2 y v2 → v3 desde el esquema exportado: conserva filas, calcula `dayKey` en la zona del dispositivo, deja las fechas de S3 sin captura en 0; valida Room al abrir) |
| Fechas y búsqueda | `LibraryDomainTest` (origen de la fecha, rango 1990–hoy+2 días, nombres con fecha, claves de día con horario de verano, analizador de búsqueda en español y chips quitables, filtros y agrupación), `LibraryDatesTest` (escaneo local, listado de S3, fecha de captura que sube de la copia local, zona que cambia, «Completar fechas» una vez por objeto, coste), `LibrarySqlTest` (cada consulta de ambas galerías sobre Room: búsqueda, filtros, orden estable, posiciones, recuentos por día, caracteres `%` y `_` del nombre) |
| Motor de la galería | `LibraryEngineTest` (disposición de secciones y aritmética de índices, ventana paginada con LRU e invalidación, navegador con consulta que cambia y estabilización) |
| Listado en streaming | `LibraryDatesTest` y `CostPersistenceTest` (páginas escritas según llegan; un fallo a mitad conserva lo escrito, no purga y marca la lista como incompleta; el inventario sale solo de un listado completo; el respaldo reutiliza un listado de pocos minutos; una subida aparece al instante sin listar) |
| Rendimiento | `LibraryPerformanceTest` (20 000 elementos por galería en el emulador: página inicial, página profunda, recuentos por día, búsqueda, filtros y otros órdenes, con cotas generosas; el plan de la consulta por defecto usa el índice y no ordena la tabla) |
| Galería inteligente (UI) | `SmartGalleryUiTest` (secciones por día/mes/año, búsqueda «sg_ marzo 2023», sin resultados, filtro de fecha estimada, orden por nombre, salto a un año en una biblioteca larga, selección de sección; S3: lista descargada, alcance de la búsqueda, **cero peticiones a S3 al buscar/filtrar**, objeto subido desde fuera que solo aparece tras actualizar, vista de lista) |
| Flujos de UI | `PhotoManagementUiTest` (sobre la cuadrícula nueva de S3): selección → «¿qué copia?» → confirmación → progreso → resultado; casilla obligatoria si solo existe en S3; IAM sin permiso explicada y reintento; biblioteca de S3 con miniaturas y visor |

### Tipos de prueba

- **Unitarias con dobles**: `FakeRemote` (S3 en memoria con inyección de fallos; registra cada `delete` para demostrar qué se borró y qué no), `FakeReader`, `FakeSink`, `FakeScheduler`.
- **Repositorios**: Room en memoria bajo Robolectric (índice, DAOs, transiciones atómicas).
- **SDK real contra servidor simulado**: `MockWebServer` + AWS SDK for Kotlin (firma SigV4, paginación, errores XML, cabeceras de checksum). `S3RemoteStorage` acepta `endpointOverride` solo para esto; el código de producción nunca lo establece.
- **Instrumentadas en dispositivo**: Keystore real, MediaStore real, Room real, SDK sobre ART, UI Compose real. `src/debug` permite HTTP a `localhost` únicamente para el servidor simulado (`FakeS3Dispatcher`, equivalente mínimo a S3: PUT/HEAD/GET/List, valida `x-amz-checksum-sha256`; además `Range`, `DeleteObjects` y los cuerpos `aws-chunked` que el SDK usa por HTTP). La política IAM «sin permiso de borrado» se simula con `allowDelete = false`.
- **Tráfico real a AWS sin cuenta**: `RealAwsErrorClassificationTest` envía una solicitud HTTPS al endpoint real de S3 con la credencial de *ejemplo de la documentación de AWS* y un bucket inexistente; comprueba la clasificación (`403 InvalidAccessKeyId` ⇒ `CREDENTIALS_INVALID`) y que no se filtra nada. Se omite si el emulador no tiene Internet. No toca ningún recurso.

## Funciones nuevas: herramientas de la biblioteca, búsqueda por contenido y álbumes privados

| Función | Pruebas |
|---|---|
| Centro de sincronización | `SyncCenterTest` (recuentos, estados, reintentos, tareas interrumpidas), `SyncCenterUiTest` (pantalla real) |
| Auditoría del respaldo | `IntegrityAuditorTest` (cada veredicto, «Falta» solo con listado completo, ETag solo por igualdad, solicitudes contadas, credenciales caducadas a mitad), `IntegrityUiTest` |
| Liberar espacio | `SpaceRecoveryTest` (solo «Verificadas», reglas que solo acortan, puerta final con `HEAD`, nunca borra por estar en cola), `SpaceRecoveryUiTest` |
| Duplicados | `SimilarityTest` (hash perceptual, agrupación), `DuplicateScannerTest` (hash por tamaño, grupos persistidos, descartes), `DuplicatesUiTest` |
| Álbumes inteligentes | `SmartAlbumsTest` (filtro → SQL, recuentos en una pasada), `SmartAlbumsUiTest` |
| Búsqueda por contenido | `SemanticTest` (codec int8, top-K, descarga reanudable con huellas, pack verificado), `ClipTokenizerTest` (coincidencia con el tokenizador de referencia), `QueryTranslatorTest`, `SemanticModelDeviceTest` (**modelo real** en el emulador: coseno frente a la referencia, relevancia, indexado), `SemanticSearchUiTest` |
| Álbumes privados: cifrado | `VaultCryptoTest` (13: tamaños límite, alteración de cualquier byte, cortes, fragmentos reordenados/duplicados/entre archivos, clave equivocada, formato desconocido) |
| Álbumes privados: sesión y almacén | `PrivateSessionTest` (8: ceros al bloquear, retraso de bloqueo, pantalla apagada), `PrivateVaultStoreTest` (21: **nada legible en la base ni en los archivos**, no se crea fila si falla, tamaño distinto rechazado, sacar solo tras verificar, original nunca tocado, bloqueo a mitad de lote, 150 fotos, clave perdida) |
| Álbumes privados: pantallas | `PrivateAlbumsUiTest` (7, pantallas reales): mover desde la galería (originales intactos y archivos cifrados no-JPEG), entrada bloqueada y pantallas abiertas sustituidas al bloquear, visor y archivo alterado rechazado, sacar a la galería con los mismos bytes, sin bloqueo de pantalla, clave perdida, `FLAG_SECURE` |
| Álbumes privados: Keystore real | `PrivateKeystoreDeviceTest` (4): clave del Keystore tras el *prompt* real del sistema con PIN (crear y volver a abrir dan la misma clave), cancelar, el almacén completo, clave envuelta alterada → «clave perdida», quitar el bloqueo de pantalla → Android borra la clave |
| Migraciones | `MigrationTest` (6): 1→2, 2→3, 3→4, 4→5, **5→actual** (datos intactos, valores por defecto, tablas nuevas usables) y **10→11** (álbumes privados sin tocar nada más) |
| Rendimiento | `BulkSelectionPerfTest`, `BulkDeletePerfTest`, `BulkOperationsBenchTest` (sin regresión), `PrivateVaultBenchTest` ([performance.md](performance.md#después-de-las-herramientas-de-la-biblioteca-y-los-álbumes-privados)) |

Qué **no** se pudo probar con el dispositivo disponible: huella dactilar (el emulador solo tiene PIN), Android 10 (la rama de la clave con ventana de autenticación), un teléfono real, TalkBack, pantallas grandes y modo oscuro de las pantallas privadas (se revisaron capturas en claro), un bucket de AWS real.

## Personas: agrupación de rostros y respaldo por persona

Datos de prueba: fotografías de **dominio público** (retratos de NASA/Fuerza Aérea/Senado de EE. UU.; [atribución](../app/src/test/resources/faces/ATTRIBUTION.md)) y vectores sintéticos con similitud controlada. Ninguna foto personal ni ningún bucket real: S3 es un sustituto local. LFW solo se usó fuera del repositorio para calibrar ([face-grouping.md](face-grouping.md)).

| Qué se prueba | Pruebas | Cómo |
|---|---|---|
| **Modelos reales en JVM** (ONNX Runtime de escritorio) | `FaceModelRealTest` (8) | Hashes de los modelos incluidos; un retrato da una cara con caja válida y vector unitario; fotos de la misma persona se parecen más que las de personas distintas; en una foto de grupo de 5 caras solo una coincide con la persona de los retratos; imágenes sin cara (plana, ruido, degradado) no dan nada ni fallan; la misma foto da el mismo vector; un rostro demasiado pequeño se cuenta y no se compara; coordenadas correctas con una foto ampliada |
| **Modelos reales en el emulador** | `FaceDeviceTest` (5) | ONNX Runtime de Android + decodificador de la plataforma: lo mismo que arriba; una foto guardada de lado con etiqueta EXIF se analiza derecha y el recorte que se dibuja contiene la cara; archivo dañado / inaccesible; **0 bytes enviados y recibidos** durante el análisis (`TrafficStats` por UID) y coste medido; ruta completa: indexar fotos reales en MediaStore, crear una persona, activar la regla, el motor de respaldo real sube **solo** esas fotos, enteras (SHA-256 idéntico), con el prefijo, sin datos faciales ni borrados |
| Geometría, decodificador de YuNet, calidad | `FaceGeometryTest` (8) | Ajuste y alineación, bordes en negro, lienzo del detector, decodificación y supresión de solapes, veredictos de calidad |
| Sellado y códec de huellas | `FaceVaultTest` (5) | Ida y vuelta, alteración de cualquier byte, otra finalidad, otra clave, clave destruida, clave ilegible, error de cuantización < 10⁻⁴ |
| Agrupación | `FaceGroupingTest` (13) | Colocar/ sugerir / dejar suelto, grupos bloqueados y misma foto, grupos nuevos (mínimos de rostros y fotos, ráfagas), cadenas de parecidos, ofertas de fusión, 40 personas sin ninguna colocación errónea |
| Política de respaldo | `FaceBackupPolicyTest` (12) | Cada veredicto: confirmado, automático seguro/dudoso/ambiguo/baja calidad, sugerencia, grupo sin revisar, política «probables», varias caras, estados que no se tocan, copia borrada a propósito, exclusiones, fotos antiguas |
| Estados de la pantalla | `PeoplePhaseTest` (14) | Los siete estados pedidos y sus bordes |
| Análisis incremental, correcciones | `FaceIndexTest` (26) | Fotos analizadas una vez, presupuesto por ejecución, cancelación y reanudación sin duplicados, archivo ilegible / sin permiso, caras pequeñas contadas, clave perdida (con y sin datos), sin modelo, cuota de rostros y poco espacio, grupos que se forman y no se duplican, correcciones que sobreviven a un reanálisis (misma fila, grupo, confirmación y rechazos), fotos que salen del dispositivo, nombrar/quitar/separar/combinar/sugerencias/ocultar/borrar, **nada fuera del índice cambia**, trabajo pesado fuera del hilo del llamador |
| **Reglas de respaldo** | `FaceBackupRuleTest` (23) | Sin regla no se encola; solo fotos nuevas; existentes con vista previa = lo encolado y exclusiones; idempotencia; sin S3; estados que no se tocan; copia de S3 borrada a propósito; foto que dejó el dispositivo; dudosas a revisión y decisiones del usuario; varias personas en una foto (una vez, entera); persona oculta; política probables; pausa y reanudación; deshacer; quitar regla/borrar persona sin borrar nada; pasada única interrumpida y reanudada tras «reinicio»; **desactivar la regla durante el análisis**; con el motor de respaldo real: subida única bajo el prefijo, **caída de red** (siguen en cola, se reintentan una vez), **error de permisos de AWS** (cola intacta, no se reencola), respaldo en pausa |
| Escala | `FaceScaleTest` (1) | 600 rostros nuevos entre 12 000 agrupados, 3 000 sueltos y 300 grupos: 434 ms, 0 colocaciones erróneas |
| Privacidad (estática) | `FacePrivacyGuardTest` (9) | El código de rostros no importa red ni S3; el trabajo no pide red; los registros solo llevan texto constante (se comprobó con una mutación); nada de rostros se escribe en archivos ni MediaStore; permisos del manifiesto sin cambios; sin copias del sistema; huellas selladas; **toda entrada a la función (herramienta, franja de Álbumes, fila de Ajustes, página del tutorial, tema de ayuda, rutas, trabajo) respeta el interruptor `-PwithFaces`** (se comprobó con una mutación) |
| Pantallas reales | `PeopleUiTest` (11) | Nada se analiza hasta aceptar la explicación; «Activar» lanza el análisis real con el trabajador real; nombrar y quitar una foto sin tocarla; la regla (vista previa, excluir, confirmar, el trabajador sube solo lo confirmado); deshacer; sin S3; sugerencias; grupo sin revisar bloquea «Continuar»; borrar el índice deja fotos y estados intactos; **tema oscuro y texto al 150 %** (capturas) |
| Migración | `MigrationTest.version11To12…` | Base v11 con datos: nada existente cambia, tablas nuevas usables y borrado en cascada |

**Compilación sin la función (`-PwithFaces=false`), comprobada a mano en el emulador el 27 de octubre de 2026:** el paquete no contiene `assets/faces` (`unzip -l`); instalada, ni la pestaña Álbumes, ni Ajustes, ni el menú de herramientas de la biblioteca, ni la ayuda muestran ningún texto de personas o rostros (volcado de `uiautomator`). Esa comprobación encontró un fallo real —la franja «Personas» de Álbumes seguía visible— que se corrigió y se añadió a la guarda estática. Las pruebas de interfaz de Personas (`PeopleUiTest`) no aplican a esa compilación: se ejecutan con la función (11/11 OK, repetidas tras el cambio; la primera ejecución falló por un diálogo del sistema «systemui no responde» tras el arranque en frío del emulador y pasó al repetirla).

**Mutaciones comprobadas** (se rompió el código a propósito y las pruebas lo detectaron): quitar la protección de `backupExcluded` en `enqueueForRule` y quitar el filtro de estado de la política → fallan 5 pruebas; meter `java.net.URL` o una traza con interpolación en el código de rostros → fallan las guardas de privacidad.

**Fallos encontrados y arreglados por las pruebas:** margen guardado como 0 para los miembros de un grupo nuevo (la política estricta los llamaba «ambiguos» → ahora se recalcula al evaluar); el propio rostro contaba en el centro del grupo que lo juzgaba (→ medición sin él); `openFd` no sirve con assets comprimidos al extraer los modelos; el orden dentro de un lote de análisis no es determinista (una prueba lo suponía); vista previa de un grupo sin revisar incoherente con la activación (→ la hoja exige confirmar el grupo).

**Una prueba preexistente tocada:** `PrivateVaultBenchTest.rawEncryptionThroughput` usaba 32 MiB y necesitaba ≈160 MB a la vez en un montón de 192 MB, de modo que pasaba por pocos MB de margen y falló al ejecutarse detrás de otras pruebas (no hay fuga: tras recolectar quedan 8–12 MB). Ahora usa 16 MiB; mide MiB/s, que no depende del tamaño.

**Entorno de las pruebas JVM con los modelos reales.** Usan el ONNX Runtime de escritorio (1.31.0; el de la app es el de Android 1.28.0, se excluye del classpath de pruebas). En esta máquina Windows, todos los JDK instalados traen un `msvcp140.dll` antiguo que hace caer la JVM al cargar ONNX Runtime; se ejecutan con `-PtestJavaHome=<copia del JDK con las DLL del sistema>`. En Linux/macOS o con un JDK con runtime reciente no hace falta. Sin eso, la JVM de pruebas se cae (error nativo en `msvcp140.dll`) en cuanto una prueba carga los modelos, es decir, en `FaceModelRealTest`; el resto de las clases corre igual si se excluye esa.

**Qué no se pudo probar:** un móvil ARM real (tiempos, batería, calor), una biblioteca de decenas de miles de fotos reales, fotos personales (por privacidad), reinicio real del dispositivo con trabajos pendientes (la recuperación se cubre con interrupciones de proceso simuladas y el estado persistido), un bucket de AWS real, TalkBack, Android 10 y tabletas.

## Ejecutar

```bash
./gradlew testDebugUnitTest      # en Windows con JDK de runtime C++ antiguo: añadir -PtestJavaHome=<jdk>, ver «Personas» arriba
./gradlew connectedDebugAndroidTest                                           # requiere emulador/dispositivo
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.s3vault.app.AppFlowTest
```

Informes: `app/build/reports/tests/`, `app/build/outputs/androidTest-results/`.

Emulador: cualquier imagen API 29+ vale. Usa Espresso 3.7.0 (ya fijado): versiones anteriores fallan en Android 17 por una API oculta eliminada. Si tu SDK no trae `cmdline-tools` (sin `avdmanager`) puedes crear el AVD a mano con `config.ini` apuntando a la imagen instalada. Los tests de UI limpian credenciales y configuración antes de cada caso.

### Pruebas de rendimiento de operaciones masivas

`BulkOperationsBenchTest`, `BulkDeletePerfTest` y `BulkSelectionPerfTest` miden en el dispositivo (tiempo de hilo de UI por
fotograma, latencia del hilo principal, memoria, duración de cada operación) y escriben el resultado en Logcat con la
etiqueta `BulkPerf`; `BulkCloudDeleteUiTest` comprueba que un borrado masivo en S3 no bloquea la pantalla, que se puede
cancelar y que un fallo parcial se informa y se reintenta solo para lo fallido. Cifras, método y límites: [performance.md](performance.md).
Estas pruebas crean fotos en `Pictures/S3VaultBulkPerf`; si una ejecución se interrumpe pueden quedar allí.

### Notas de las pruebas de borrado y vista previa

- Los archivos que crea la propia prueba son suyos: Android los deja borrar sin preguntar con `ContentResolver.delete`, pero `createDeleteRequest` sigue mostrando el diálogo. `PhotoManagementUiTest` lo gestiona: Compose solo dibuja fotogramas mientras el hilo de pruebas está dentro de una llamada a Compose, así que tras la confirmación hay que ejecutar un `waitForIdle()` antes de esperar a que Android muestre su diálogo, y se pulsa «Permitir» por posición relativa (calibrada en el AVD de 1080×2400).
- `MigrationTestHelper` de Room no se puede usar porque el proyecto fija `kotlinx-serialization` a 1.7.3 (lo exige el SDK de AWS) y el helper necesita una versión más nueva; la prueba reconstruye la BD v1 desde `1.json` y deja que Room migre y valide.
- Las capturas de los flujos se guardan en `Android/data/com.s3vault.app/files/shots` del dispositivo (no se versionan).

## Prueba de integración con un bucket real (opt-in, no ejecutada)

Usa un **bucket de prueba independiente** con el prefijo `s3vault-it/` y la política de [iam-policy.md](iam-policy.md):

1. Configura la app con ese bucket y credenciales temporales de una sesión corta (p. ej. 15 min).
2. «Probar conexión» y «Probar permisos»: deben dar ✓ en List/Put/Get.
3. Respalda 3 fotos; comprueba con `aws s3 ls --recursive s3://BUCKET/s3vault-it/` las claves `photos/{assetId}/{sha256}/{nombre}` y con `aws s3api head-object --checksum-mode ENABLED` el `ChecksumSHA256`.
4. Vuelve a respaldar: no debe haber nuevos `PUT`.
5. Deja caducar la sesión durante un respaldo: debe aparecer el aviso, la cola debe conservarse y, al pegar credenciales nuevas, completarse sola.
6. Edita una foto (recorte): debe aparecer un segundo objeto bajo el mismo `assetId`; el primero sigue ahí.
7. Elimina una foto del teléfono: el objeto sigue en S3; restáurala desde «S3» y compara `sha256sum`.
7b. Con la política de [«Permitir borrar desde la app»](iam-policy.md#permitir-borrar-desde-la-app-opcional): en la pestaña S3 elige una foto → «Eliminar de S3». Comprueba con `aws s3 ls --recursive` que desaparecen la foto y `.s3vault/thumbs/<sha256>.jpg`, y que `.s3vault/albums.json` sigue. Con el `Deny` original la app debe explicar que la política no lo permite.
7c. Mira una foto en la pestaña S3 y revisa en CloudTrail / métricas de S3 que solo hubo un `GET` de la miniatura (y nunca del original).
7d. Álbumes de S3: con fotos migradas solo a S3 y un `.s3vault/albums.json` válido, Álbumes → icono de nube debe crear los álbumes con sus fotos (vistas previas, sin `GET` de originales). «Quitar del álbum» no toca el objeto.
8. Quita `GetObject` de la política: «Probar permisos» debe marcar ✗ solo en `s3:GetObject`.
9. Cambia la región por una equivocada: el mensaje debe indicar la región correcta.

## Validación manual pendiente

Requieren dispositivo real y/o cuenta AWS y **no se han ejecutado**:

- Subida/verificación/restauración extremo a extremo contra S3 real (el servidor simulado cubre el protocolo, no las particularidades de AWS: firma sobre HTTPS con `UNSIGNED-PAYLOAD`, `ChecksumMode`, comportamiento del 403/404 en `HEAD`).
- Credenciales STS reales (`assume-role`, `get-session-token`, `export-credentials` con SSO) y el formato exacto de `Expiration` de tu versión de la CLI.
- `WorkManager` periódico con pantalla apagada, Doze, ahorro de batería y *standby buckets*; reinicio real del dispositivo.
- **«Personas» en un móvil ARM real** (tiempo por foto, batería, calor, calidad de los grupos con fotos propias). **No se ha hecho.**
- Rendimiento y memoria con bibliotecas de **100 000+** fotos y en un dispositivo real de gama baja (con 20 000 por galería se midió en el emulador, ver `LibraryPerformanceTest`; el tiempo del primer escaneo de MediaStore con muchos archivos no se midió). Un listado de S3 de decenas de miles de objetos contra AWS real tampoco.
- Fechas de captura reales: en el emulador MediaProvider ignora el `DATE_TAKEN` que escribe una prueba, así que las pruebas de interfaz usan fechas en el nombre del archivo; la lectura de `DATE_TAKEN`/EXIF está cubierta por las pruebas unitarias del resolvedor, no por una foto de cámara real.
- Hashing y subida de archivos grandes (varios cientos de MB) y de vídeos; límite de 4 GiB sin multipart.
- Acceso parcial a fotos de Android 14+ (selección limitada) y revocación del permiso durante un respaldo.
- Fotos HEIC/RAW y vídeos: miniaturas y reproducción en el visor según el dispositivo.
- Accesibilidad (TalkBack), tamaños de fuente grandes, tabletas y orientación horizontal.
- Con un bucket que use SSE-KMS gestionada por el cliente (permisos KMS).
- **Borrado local en Android 10 (API 29)** (`RecoverableSecurityException`, un diálogo por foto): implementado pero no ejecutado; el AVD es de API 37. En Android 11+ se probó con el diálogo real de `createDeleteRequest`, tanto a mano como en `PhotoManagementUiTest`.
- Comportamiento real de `DeleteObjects` de AWS (la firma con suma de comprobación, un bucket con *Versioning* y reglas de ciclo de vida con `GLACIER_IR`): el servidor simulado cubre el protocolo y el SDK real, no las particularidades de AWS.
- Miniaturas de HEIC/AVIF, RAW y vídeos largos en dispositivos reales (`ContentResolver.loadThumbnail` depende del códec del fabricante) y el tamaño real de miniatura con fotos de cámara (la estimación de 60 KB está sin medir en tu biblioteca).
- **Precios reales de tu cuenta:** la estimación usa precios de lista; no se ha contrastado con una factura real de AWS ni con Cost Explorer.
- Buckets con cientos de miles de objetos: el inventario hace una solicitud LIST por cada 1000 y se mantiene en memoria solo mientras se agrega; no se ha medido el tiempo con listados enormes.
- Rendimiento del relleno de miniaturas con decenas de miles de fotos y con ahorro de batería (se hacen 300 por ejecución del *worker*).

## Tutorial, ayuda y preparación para publicar

| Área | Pruebas |
|---|---|
| Decisión de mostrar el tutorial (instalación nueva, interrumpida, terminada, existente) | `OnboardingTest` (10, lógica pura: política y lista de configuración), `OnboardingGateTest` (6, con el contenedor real: Room y ajustes reales; persistencia entre reinicios) |
| Tutorial en la app real | `OnboardingUiTest` (10): primera apertura y **omitir sin bloquear la galería**, recorrer las seis páginas y no repetirse, **cerrar a la mitad y volver a verlo**, instalación existente no interrumpida, **reabrir desde Ayuda sin cambiar el estado guardado**, paso de S3 vacío, conexión correcta con **una sola solicitud de lectura**, **permiso denegado**, **bucket inexistente** y **sin red** (con un S3 simulado local; no se usa ningún bucket real) |
| Permisos de fotos | `OnboardingRenderTest`: concedido, parcial y denegado (se renderizan los tres estados; **no** se puede revocar el permiso desde una prueba instrumentada sin matar la app) |
| Accesibilidad, texto grande y tema oscuro | `OnboardingRenderTest` (tutorial completo con **texto al 200 %** y tema oscuro: botones visibles y con altura táctil ≥ 48 dp en cada página; contenido desplazable), `ThemeAuditTest` (pantallas principales en tema oscuro). Encontró un error real: los títulos eran negros sobre fondo oscuro |
| Ayuda | `OnboardingUiTest` (apertura y cierre de temas, versión, enlace a incidencias) y `OnboardingRenderTest` (texto grande y tema oscuro) |
| Aislamiento de las pruebas | `S3VaultTestRunner` registra el tutorial como visto antes de arrancar la app para que el resto de pruebas empiece en la galería |
| Errores de conexión comprensibles | `BackupEngineTest` (mensaje de archivo demasiado grande), tutorial y ajustes (mensajes de bucket, permisos y red) |

El tutorial está probado en **emulador** con Android 17; no se ha probado con lectores de pantalla (TalkBack), en una tableta ni en un teléfono real.

Nota técnica: el almacén de ajustes ahora se crea **por archivo** (mismo archivo que antes, `files/datastore/settings.preferences_pb`), de modo que las instalaciones existentes conservan sus preferencias y cada prueba de Robolectric tiene el suyo. Antes era un singleton del proceso que compartían todas las pruebas y fallaba de forma intermitente en Windows («Unable to rename … settings.preferences_pb.tmp»). Tres ejecuciones seguidas de la suite unitaria completa pasaron sin ningún fallo.

### Fallos intermitentes conocidos y arreglos de aislamiento de esta ronda

- **DataStore en Robolectric sobre Windows.** Antes: un *singleton* de proceso compartido por todas las pruebas que fallaba casi en cada pasada con «Unable to rename … settings.preferences_pb.tmp». Se corrigió con un almacén por archivo (ver arriba). **Queda** un fallo mucho más raro del mismo tipo *dentro de una misma prueba* (el sistema de archivos de Windows no deja renombrar el temporal): apareció una vez en las pasadas de esta ronda (`CostPersistenceTest`) y no se repitió en dos pasadas posteriores. Es un fallo de entorno, no de la app.
- **`IntegrityUiTest`**: dependía de que no hubiera un reescaneo de MediaStore pendiente de una clase anterior (que marcaba como ausentes las filas sembradas y desactivaba «Volver a subir»). La prueba ahora lo agota antes de sembrar; se comprobó en los dos órdenes que lo reproducían.
- **`SmartGalleryUiTest` / `SmartAlbumsUiTest`**: arreglos de aislamiento de la ronda anterior (fotos de relleno propias; reinicio del filtro compartido).
- **Disco del equipo lleno** durante una pasada: el *worker* de pruebas murió con «File too large». Tras liberar espacio, todo pasó. Si ocurre, no es un fallo del código.
