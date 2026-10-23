# Pruebas

## Resultado de la última ejecución

| Suite | Comando | Resultado |
|---|---|---|
| Unitarias (JVM + Robolectric) | `./gradlew testDebugUnitTest` | **168 pruebas, 0 fallos** |
| Instrumentadas (emulador Android 17 / API 37, x86_64) | `./gradlew connectedDebugAndroidTest` | **34 pruebas, 0 fallos** |
| Lint | `./gradlew lintDebug` | 0 errores, 37 avisos (versiones/estilo) |
| Build release con R8 | `./gradlew assembleRelease` | OK (≈ 4,9 MB); arranca y navega sin fallos en el emulador (firmada con la clave de depuración) |

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
| Migración de Room | `MigrationTest` (v1 → v2 desde el esquema exportado, valida Room al abrir) |
| Flujos de UI | `PhotoManagementUiTest`: selección → «¿qué copia?» → confirmación → progreso → resultado; casilla obligatoria si solo existe en S3; IAM sin permiso explicada y reintento; biblioteca de S3 con miniaturas y visor |

### Tipos de prueba

- **Unitarias con dobles**: `FakeRemote` (S3 en memoria con inyección de fallos; registra cada `delete` para demostrar qué se borró y qué no), `FakeReader`, `FakeSink`, `FakeScheduler`.
- **Repositorios**: Room en memoria bajo Robolectric (índice, DAOs, transiciones atómicas).
- **SDK real contra servidor simulado**: `MockWebServer` + AWS SDK for Kotlin (firma SigV4, paginación, errores XML, cabeceras de checksum). `S3RemoteStorage` acepta `endpointOverride` solo para esto; el código de producción nunca lo establece.
- **Instrumentadas en dispositivo**: Keystore real, MediaStore real, Room real, SDK sobre ART, UI Compose real. `src/debug` permite HTTP a `localhost` únicamente para el servidor simulado (`FakeS3Dispatcher`, equivalente mínimo a S3: PUT/HEAD/GET/List, valida `x-amz-checksum-sha256`; además `Range`, `DeleteObjects` y los cuerpos `aws-chunked` que el SDK usa por HTTP). La política IAM «sin permiso de borrado» se simula con `allowDelete = false`.
- **Tráfico real a AWS sin cuenta**: `RealAwsErrorClassificationTest` envía una solicitud HTTPS al endpoint real de S3 con la credencial de *ejemplo de la documentación de AWS* y un bucket inexistente; comprueba la clasificación (`403 InvalidAccessKeyId` ⇒ `CREDENTIALS_INVALID`) y que no se filtra nada. Se omite si el emulador no tiene Internet. No toca ningún recurso.

## Ejecutar

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest                                           # requiere emulador/dispositivo
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.s3vault.app.AppFlowTest
```

Informes: `app/build/reports/tests/`, `app/build/outputs/androidTest-results/`.

Emulador: cualquier imagen API 29+ vale. Usa Espresso 3.7.0 (ya fijado): versiones anteriores fallan en Android 17 por una API oculta eliminada. Si tu SDK no trae `cmdline-tools` (sin `avdmanager`) puedes crear el AVD a mano con `config.ini` apuntando a la imagen instalada. Los tests de UI limpian credenciales y configuración antes de cada caso.

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
8. Quita `GetObject` de la política: «Probar permisos» debe marcar ✗ solo en `s3:GetObject`.
9. Cambia la región por una equivocada: el mensaje debe indicar la región correcta.

## Validación manual pendiente

Requieren dispositivo real y/o cuenta AWS y **no se han ejecutado**:

- Subida/verificación/restauración extremo a extremo contra S3 real (el servidor simulado cubre el protocolo, no las particularidades de AWS: firma sobre HTTPS con `UNSIGNED-PAYLOAD`, `ChecksumMode`, comportamiento del 403/404 en `HEAD`).
- Credenciales STS reales (`assume-role`, `get-session-token`, `export-credentials` con SSO) y el formato exacto de `Expiration` de tu versión de la CLI.
- `WorkManager` periódico con pantalla apagada, Doze, ahorro de batería y *standby buckets*; reinicio real del dispositivo.
- Rendimiento y memoria con bibliotecas grandes (≥ 20 000 fotos): la cuadrícula observa la lista completa desde Room; medir y, si hace falta, paginar. Primer escaneo de MediaStore con muchos archivos.
- Hashing y subida de archivos grandes (varios cientos de MB) y de vídeos; límite de 4 GiB sin multipart.
- Acceso parcial a fotos de Android 14+ (selección limitada) y revocación del permiso durante un respaldo.
- Fotos HEIC/RAW y vídeos: miniaturas y reproducción en el visor según el dispositivo.
- Accesibilidad (TalkBack), tamaños de fuente grandes, tabletas y orientación horizontal.
- Con un bucket que use SSE-KMS gestionada por el cliente (permisos KMS).
- **Borrado local en Android 10 (API 29)** (`RecoverableSecurityException`, un diálogo por foto): implementado pero no ejecutado; el AVD es de API 37. En Android 11+ se probó con el diálogo real de `createDeleteRequest`, tanto a mano como en `PhotoManagementUiTest`.
- Comportamiento real de `DeleteObjects` de AWS (la firma con suma de comprobación, un bucket con *Versioning* y reglas de ciclo de vida con `GLACIER_IR`): el servidor simulado cubre el protocolo y el SDK real, no las particularidades de AWS.
- Miniaturas de HEIC/AVIF, RAW y vídeos largos en dispositivos reales (`ContentResolver.loadThumbnail` depende del códec del fabricante) y el tamaño real de miniatura con fotos de cámara (la estimación de 60 KB está sin medir en tu biblioteca).
- Rendimiento del relleno de miniaturas con decenas de miles de fotos y con ahorro de batería (se hacen 300 por ejecución del *worker*).
