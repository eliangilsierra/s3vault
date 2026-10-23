# Pruebas

## Resultado de la última ejecución

| Suite | Comando | Resultado |
|---|---|---|
| Unitarias (JVM + Robolectric) | `./gradlew testDebugUnitTest` | **102 pruebas, 0 fallos** |
| Instrumentadas (emulador Android 17 / API 37, x86_64) | `./gradlew connectedDebugAndroidTest` | **18 pruebas, 0 fallos** |
| Lint | `./gradlew lintDebug` | 0 errores (avisos de versiones/estilo) |
| Build release con R8 | `./gradlew assembleRelease` | OK (≈ 4,9 MB); flujo de conexión verificado a mano en el emulador |

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
| Hilo principal | `s3CallsFromTheMainThreadNeverTouchTheNetworkOnIt` (reproduce un bug real hallado en la build release) |

### Tipos de prueba

- **Unitarias con dobles**: `FakeRemote` (S3 en memoria con inyección de fallos, sin operación de borrado), `FakeReader`, `FakeSink`, `FakeScheduler`.
- **Repositorios**: Room en memoria bajo Robolectric (índice, DAOs, transiciones atómicas).
- **SDK real contra servidor simulado**: `MockWebServer` + AWS SDK for Kotlin (firma SigV4, paginación, errores XML, cabeceras de checksum). `S3RemoteStorage` acepta `endpointOverride` solo para esto; el código de producción nunca lo establece.
- **Instrumentadas en dispositivo**: Keystore real, MediaStore real, Room real, SDK sobre ART, UI Compose real. `src/debug` permite HTTP a `localhost` únicamente para el servidor simulado (`FakeS3Dispatcher`, equivalente mínimo a S3: PUT/HEAD/GET/List, valida `x-amz-checksum-sha256`).
- **Tráfico real a AWS sin cuenta**: `RealAwsErrorClassificationTest` envía una solicitud HTTPS al endpoint real de S3 con la credencial de *ejemplo de la documentación de AWS* y un bucket inexistente; comprueba la clasificación (`403 InvalidAccessKeyId` ⇒ `CREDENTIALS_INVALID`) y que no se filtra nada. Se omite si el emulador no tiene Internet. No toca ningún recurso.

## Ejecutar

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest                                           # requiere emulador/dispositivo
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.s3vault.app.AppFlowTest
```

Informes: `app/build/reports/tests/`, `app/build/outputs/androidTest-results/`.

Emulador: cualquier imagen API 29+ vale. Usa Espresso 3.7.0 (ya fijado): versiones anteriores fallan en Android 17 por una API oculta eliminada. Si tu SDK no trae `cmdline-tools` (sin `avdmanager`) puedes crear el AVD a mano con `config.ini` apuntando a la imagen instalada. Los tests de UI limpian credenciales y configuración antes de cada caso.

## Prueba de integración con un bucket real (opt-in, no ejecutada)

Usa un **bucket de prueba independiente** con el prefijo `s3vault-it/` y la política de [iam-policy.md](iam-policy.md):

1. Configura la app con ese bucket y credenciales temporales de una sesión corta (p. ej. 15 min).
2. «Probar conexión» y «Probar permisos»: deben dar ✓ en List/Put/Get.
3. Respalda 3 fotos; comprueba con `aws s3 ls --recursive s3://BUCKET/s3vault-it/` las claves `photos/{assetId}/{sha256}/{nombre}` y con `aws s3api head-object --checksum-mode ENABLED` el `ChecksumSHA256`.
4. Vuelve a respaldar: no debe haber nuevos `PUT`.
5. Deja caducar la sesión durante un respaldo: debe aparecer el aviso, la cola debe conservarse y, al pegar credenciales nuevas, completarse sola.
6. Edita una foto (recorte): debe aparecer un segundo objeto bajo el mismo `assetId`; el primero sigue ahí.
7. Elimina una foto del teléfono: el objeto sigue en S3; restáurala desde «S3» y compara `sha256sum`.
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
