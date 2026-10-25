# Sincronización: respaldo y restauración

## Máquina de estados

```mermaid
stateDiagram-v2
  [*] --> LOCAL_ONLY: archivo nuevo en MediaStore
  LOCAL_ONLY --> QUEUED: respaldar (manual / automático)
  QUEUED --> UPLOADING: worker toma el elemento
  UPLOADING --> VERIFYING: PUT correcto (o contenido ya en S3)
  VERIFYING --> BACKED_UP: HEAD confirma tamaño + SHA-256
  UPLOADING --> QUEUED: red / throttling / caducidad / cancelación
  VERIFYING --> QUEUED: verificación fallida (reintento)
  QUEUED --> FAILED: error permanente o reintentos agotados
  FAILED --> QUEUED: «Reintentar»
  BACKED_UP --> LOCAL_ONLY: el archivo cambió (nueva versión)
  BACKED_UP --> REMOTE_ONLY: el archivo ya no está en el teléfono
  REMOTE_ONLY --> RESTORING: restaurar
  RESTORING --> REMOTE_ONLY: terminada / cancelada / error
```

- Implementada en `SyncStateMachine`; cada cambio pasa por `require(from, to)` y por un `UPDATE ... WHERE syncState = :from` atómico en Room, de modo que dos escritores no pueden pisarse.
- **Nunca** se pasa a `BACKED_UP` sin `VERIFYING`; no existe un booleano «subido».
- Recuperación tras cierre/reinicio: al iniciar el motor, `UPLOADING`/`VERIFYING` → `QUEUED`. `RESTORING` (en la biblioteca remota) **sí** se conserva: es la cola de restauraciones persistente y WorkManager la reanuda.
- `REMOTE_ONLY`/`RESTORING` describen también las filas de la biblioteca remota (`remote_objects`); en el índice local, `REMOTE_ONLY` significa «tuvo copia verificada y el archivo local desapareció» (`present = false`).

## Claves en S3

```
{prefijo}/photos/{assetId}/{versionId}/{nombreSanitizado}
```

- `assetId`: UUID aleatorio (32 hex) asignado una vez por archivo indexado. Dos archivos con el mismo nombre nunca colisionan.
- `versionId`: **SHA-256 del contenido** (64 hex). Contenido distinto ⇒ clave distinta ⇒ la copia anterior nunca se sobrescribe ni se pierde.
- `nombreSanitizado`: ASCII seguro (`[A-Za-z0-9._-]`), solo para poder navegar el bucket y mostrar nombres sin un `HEAD` por objeto. El nombre original exacto va en el metadato `original-name`.
- Reinstalar la app genera `assetId` nuevos, pero la deduplicación por contenido (abajo) evita volver a subir lo que ya está.

## Cuándo se calcula un hash

| Momento | ¿Hash? |
|---|---|
| Abrir la app / escanear MediaStore | **Nunca** |
| Antes de subir un elemento de la cola | Sí, en streaming (128 KiB) en `Dispatchers.IO` |
| Elemento ya con hash válido | No: se reutiliza |

El hash se guarda en Room junto con el `(tamaño, fecha de modificación)` de los bytes que lo produjeron. Solo es válido mientras ambos coincidan (`hashIsValid()`); si cambian, se invalida y se recalcula la próxima vez que haga falta. Un cambio de fecha sin cambio de contenido recalcula el hash pero **no** vuelve a subir (mismo `versionId` ⇒ se verifica con `HEAD`).

## Evitar transferencias innecesarias

1. `BACKED_UP` no se puede volver a encolar.
2. Al iniciar una tanda se hace un barrido `LIST` de `{prefijo}/photos/` (caché en Room), salvo que el último listado completo tenga menos de 10 minutos (`refreshIfStale`): la deduplicación solo necesita saber qué hay, y unos minutos de antigüedad no cuestan nada, mientras que un barrido cuesta una petición por cada 1000 objetos. Las subidas terminadas se anotan al instante en la caché (`recordUpload`). Si ya existe un objeto con el mismo `versionId` y tamaño (esta instalación tras un fallo, una restauración, otro dispositivo), se enlaza esa clave, se verifica con `HEAD` y se marca respaldado **sin subir**.
3. Antes de subir se comprueba si la clave exacta ya existe (`HEAD`), útil tras una subida interrumpida que sí terminó.
4. Si S3 contesta 403 (política con `ListBucket` por prefijo) al `HEAD` de un objeto inexistente, se cae a la subida normal.

## Archivos modificados

Detectado en el escaneo: `BACKED_UP` con `(tamaño, mtime)` distintos de los respaldados → `LOCAL_ONLY` (limpia error e intentos). Al respaldarlo de nuevo se hashea, sale un `versionId` nuevo y se sube **otro objeto bajo el mismo `assetId`**. La versión anterior se conserva (no hay borrado). La fila apunta a la versión más reciente. Si la nueva subida falla, la copia antigua sigue siendo válida en S3.

## Integridad

- Subida: `PutObject` con `x-amz-checksum-sha256` calculado localmente. S3 rechaza el cuerpo si no coincide (`BadDigest`).
- Verificación: `HEAD` con `ChecksumMode=ENABLED`; tamaño **y** checksum deben coincidir. En subidas nuevas el checksum es obligatorio; para objetos preexistentes sin checksum se acepta solo si el metadato `sha256-hex` coincide.
- **No se usa el ETag como MD5** (no lo es con SSE-KMS o multipart).
- Restauración: el `versionId` de la clave *es* el SHA-256 del original; el archivo descargado se verifica contra él y contra el tamaño antes de publicarse.

## Subidas con WorkManager

- `BackupWorker` (una sola cola `KEEP` + trabajo periódico para el modo automático) con restricciones: red conectada o **no medida** (solo Wi-Fi), y batería no baja para el periódico. Backoff exponencial (30 s base) en `retry()`.
- No se promete ejecución inmediata: Doze, ahorro de batería y *standby buckets* pueden aplazar los trabajos. No hay servicio en primer plano.
- Concurrencia 1–4 (ajustable) con `Semaphore`; el progreso por archivo es en memoria (cosmético); lo durable es Room.
- Excluir vídeos afecta al respaldo **automático** y a «Respaldar pendientes»; los vídeos seleccionados a mano se encolan siempre.
- Cambiar «solo Wi-Fi» reprograma el trabajo pendiente con la nueva restricción. Pausar cancela el worker, conserva la cola y devuelve cualquier elemento en vuelo a `QUEUED`.
- Tras reiniciar el dispositivo WorkManager reprograma el trabajo y `S3VaultApp` re-registra el periódico y relanza si hay cola.

## Clasificación de errores

| Tipo S3 | Clase | Reintenta | Efecto en el elemento |
|---|---|---|---|
| Red/timeout/5xx | `NETWORK` | Sí (backoff) | `QUEUED`, intentos+1 |
| `SlowDown`/503 | `THROTTLED` | Sí | igual |
| Checksum/verificación | `INTEGRITY_MISMATCH` | Sí | igual |
| Hasta 6 intentos | — | No | `FAILED` (reintentable a mano) |
| `ExpiredToken`, sin credenciales | `CREDENTIALS_*` | No | `QUEUED` **sin** consumir intentos; el run se detiene; aviso en UI |
| `InvalidAccessKeyId`/`SignatureDoesNotMatch` | `CREDENTIALS_INVALID` | No | se detiene y avisa |
| `AccessDenied` | `ACCESS_DENIED` | No | `FAILED` permanente + **bloquea la tanda** + mensaje al usuario |
| `NoSuchBucket`, región incorrecta, cuota | `BUCKET_NOT_FOUND`, `WRONG_REGION`, `QUOTA_EXCEEDED` | No | igual que permisos |
| Archivo ilegible / > 4 GiB | `FILE_UNAVAILABLE`, `FILE_TOO_LARGE` | No | `FAILED` permanente |

## Restauración

1. «Restaurar» en la biblioteca remota marca `RESTORING` (cola persistente) y lanza `RestoreWorker`.
2. `HEAD` → tamaño, ETag. Descarga a `cache/restore/<sha1(key)>.part`; si hay parcial con el mismo ETag se **reanuda** con `Range: bytes=N-` + `If-Match`.
3. Verifica SHA-256 + tamaño. Si falla, borra el parcial y reintenta desde cero.
4. Publica en MediaStore (`Pictures/S3Vault` o `Movies/S3Vault`) con `IS_PENDING`; si el nombre existe, MediaStore crea otro (`nombre (1).jpg`): **nunca se sobrescribe**. Si ya hay copia local del mismo contenido, la interfaz pide confirmación antes.
5. La foto restaurada aparece en la galería y se enlaza con el objeto por deduplicación de contenido (no se vuelve a subir).

## Miniaturas (vista previa en S3)

Tras verificar un respaldo (o al enlazar contenido que ya estaba en S3) el motor genera una miniatura JPEG de ≤ 720 px con `ContentResolver.loadThumbnail` y la sube a `{prefijo}/.s3vault/thumbs/{versionId}.jpg` con un `PUT`. Es **mejor esfuerzo**: un fallo nunca invalida el respaldo; la fila queda con `thumbnailUploaded = false`.

- `thumbnailUploaded` se pone a `false` en cada versión nueva, así que una foto editada tiene su propia miniatura.
- *Backfill*: tras una ejecución completa del `BackupWorker`, hasta 300 filas `BACKED_UP` con archivo en el teléfono y sin miniatura se procesan desde la copia local. Se detiene en el primer error que se repetiría para todas (credenciales, permisos, red). Un archivo que no se puede decodificar se marca como hecho para no reintentarlo eternamente.
- Se puede desactivar en **Ajustes generales → Vistas previas en S3**; en **Respaldo** hay un botón para completarlas a demanda.

## Borrar

```mermaid
flowchart TD
  U[Selección] --> C{¿Qué copia?}
  C -->|Del dispositivo| D1[Confirmación]
  D1 --> D2[Diálogo del sistema Android]
  D2 --> D3[Comprobar en MediaStore qué desapareció]
  D3 --> D4[Room: BACKED_UP → REMOTE_ONLY sin archivo; sin copia en S3 → fila eliminada]
  C -->|De Amazon S3| S1[Confirmación explícita]
  S1 --> S2[DeleteObjects de 100 en 100]
  S2 --> S3[Room tras cada lote: foto local → LOCAL_ONLY marcada; solo-S3 → fila eliminada]
```

- **Dispositivo:** `MediaStore.createDeleteRequest` (Android 11+) por lotes de 500, o `RecoverableSecurityException` foto a foto (Android 10). El resultado del diálogo no se da por bueno: se comprueba cada URI. Las filas con copia en S3 pasan a `REMOTE_ONLY` (`present = false`, la clave se conserva); las demás se eliminan del índice y de los álbumes.
- **S3:** solo claves de `photos/` (y sus miniaturas, que se borran después y solo si ningún otro objeto con el mismo contenido las usa). Cada lote se aplica a Room en cuanto S3 lo confirma, así que cancelar o fallar a medias deja el estado coherente. Un `403` por IAM detiene el resto.
- **No volver a subir:** la foto local cuya copia en S3 se borró queda con `backupExcluded = true`; «Respaldar pendientes» y el respaldo automático la saltan, y elegirla a mano y pulsar «Respaldar» quita la marca.
- **Metadatos obsoletos:** el listado de S3 es una caché. Si un objeto se borró desde fuera, la vista previa lo detecta (`404` del original) y refresca el listado; borrar un objeto que ya no existe cuenta como correcto (S3 es idempotente).

Reglas completas y costes: [cloud-previews.md](cloud-previews.md).

## Catálogo remoto sin manifiesto

Se descartó un manifiesto JSON en S3: exigiría resolver escrituras concurrentes desde varios dispositivos, escrituras interrumpidas y recuperación (S3 no tiene *compare-and-swap* simple sin ETags condicionales y bloqueos). Como la clave ya lleva `assetId`/`versionId`/nombre, un `ListObjectsV2` basta. Coste: un `LIST` por 1000 objetos al refrescar; los metadatos extendidos (fecha de captura, nombre exacto) se piden con `HEAD` solo cuando abres el detalle de un objeto. Las miniaturas de esta versión se generan en el teléfono al respaldar (ver arriba) precisamente para no tener que descargar el original.

## Listado, caché y fechas de la biblioteca remota

- `RemoteCatalog.refresh` lee `ListObjectsV2` página a página y **escribe cada página en Room al recibirla**: los primeros objetos aparecen mientras llega el resto y nunca se retiene más que una página. Si el listado falla o se cancela, las páginas ya escritas se conservan, **no se purga nada** y se guarda `ListingResult(complete = false)` para que la interfaz avise de que la lista puede ser incompleta; solo un listado que llega al final retira de la caché los objetos que ya no existen (la caché; S3 nunca se toca).
- Cada fila guarda además `effectiveDateMs`, `dateSource`, `dayKey` (0 si no hay fecha de captura) y `uploadDayKey`. Una fecha de captura ya conocida nunca se degrada por un listado posterior. Qué es una fecha de captura y cómo se obtiene sin peticiones adicionales: [smart-gallery.md](smart-gallery.md#fechas-qué-es-una-fecha-de-captura).

## Centro de sincronización y tareas

La pestaña «Respaldo» es el centro de sincronización: muestra las mismas filas de Room que usa el motor (`QUEUED`, `UPLOADING`, `FAILED`…) más el resumen persistido de la última ejecución de cada tarea (`background_jobs`: respaldo, restauración, auditoría, duplicados, búsqueda por contenido, descarga del modelo). No mantiene estado propio: lo que ves tras matar la app es lo que hay en la base de datos. Cancelar un elemento en cola solo lo saca de la cola local (no toca S3), y reintentar vuelve a encolar un fallo. Al arrancar, una tarea que figuraba como en curso en un proceso anterior se marca como interrumpida. Detalle de las demás herramientas en [library-tools.md](library-tools.md).

El marcador de versión de S3 (ETag) se guarda al respaldar y al listar **solo para comparar por igualdad** (¿se reemplazó el objeto?). Nunca se interpreta como MD5: la integridad sigue apoyándose en el SHA-256 que se envió y se verificó.
