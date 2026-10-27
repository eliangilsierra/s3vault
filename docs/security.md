# Seguridad y privacidad

## Modelo de amenazas (resumen)

| Amenaza | Mitigación | Límite |
|---|---|---|
| Otra app lee credenciales | Cifrado AES-256-GCM con clave del Keystore; archivo en almacenamiento privado sin copia de seguridad | Malware con root o ejecutándose como esta app puede usar la clave del Keystore |
| Credenciales en logs/errores/capturas | `Redactor` + `SafeLog`; mensajes de error estáticos (solo código HTTP/S3); `FLAG_SECURE` en pantallas sensibles; sin secretos en Room/DataStore | Una captura del sistema con root o ADB debug del propio usuario no se puede impedir |
| Intercepción de red | HTTPS obligatorio (`cleartextTrafficPermitted=false` en release), validación TLS estándar de Android, sin *pinning* casero ni `TrustManager` propio | `src/debug` permite HTTP a `localhost` solo para pruebas |
| Prefijo/bucket distinto al configurado | `RemoteKeys` + `S3RemoteStorage` rechazan claves fuera del prefijo y subidas fuera de `photos/` aunque la UI falle; `ListObjectsV2` siempre con prefijo; política IAM limitada | La política IAM del usuario es el límite real: configúrala (ver [iam-policy.md](iam-policy.md)) |
| Borrado accidental o malicioso | El borrado en S3 es una acción explícita con confirmación (más una casilla si la foto solo existe en S3), nunca se borran las dos copias de un solo paso y `S3RemoteStorage.delete` solo acepta claves de fotos y miniaturas del prefijo; la política recomendada lo **prohíbe** con un `Deny` y solo se activa si el usuario lo decide ([iam-policy.md](iam-policy.md#permitir-borrar-desde-la-app-opcional)) | Quien tenga otras credenciales puede borrar desde fuera de la app; si activas el borrado, la app puede borrar las fotos y miniaturas de tu prefijo |
| Corrupción en tránsito/en reposo | SHA-256 enviado y validado por S3, `HEAD` de verificación, verificación del SHA-256 en restauración | Ver [synchronization.md](synchronization.md) (ETag no se usa como MD5) |
| Pérdida del teléfono | Credenciales temporales caducan; revoca desde AWS | El cifrado local no sustituye a revocar |
| Alguien con el móvil desbloqueado abre tus fotos privadas | Álbumes privados: cifrado AES-256-GCM, clave del Keystore que exige huella/PIN en cada desbloqueo, bloqueo automático, `FLAG_SECURE`, metadatos y miniaturas cifrados ([private-albums.md](private-albums.md)) | No protege frente a root, malware en la app con el vault abierto, ni sustituye al bloqueo del dispositivo; si se pierde la clave no hay recuperación |
| Modelo de búsqueda manipulado | Descarga HTTPS con huella SHA-256 fijada en la app, verificada antes de usarlo; si no coincide se borra | Confías en el contenido que fija la huella (OpenAI CLIP, MIT, convertido a ONNX); ver [semantic-search.md](semantic-search.md) |
| Exposición accidental del bucket | Block Public Access documentado como obligatorio; la app nunca habilita acceso público | Depende de la configuración del usuario |

## Qué datos hay dónde

**En el teléfono**

- Tus fotos y vídeos (MediaStore; la app los lee, escribe restauraciones en `Pictures/S3Vault`/`Movies/S3Vault` y **solo los borra cuando tú lo pides y Android lo confirma** con su propio diálogo; no usa `MANAGE_EXTERNAL_STORAGE` ni ningún permiso de escritura amplio).
- Caché de vistas previas de S3 (`cacheDir/remote-previews`, máx. 96 MB, sin cifrar como cualquier caché de la app; se vacía al desconectar y Android puede borrarla).
- Índice local (Room): ID de MediaStore, URI, nombre, tipo, tamaño, fechas, dimensiones, carpeta, `assetId`, hash SHA-256 (solo tras haberlo necesitado), clave remota, estado, último error (redactado), álbumes virtuales, registro de transferencias (máx. 200). **Sin secretos.**
- Preferencias (DataStore): región, bucket, prefijo, método de autenticación, opciones de respaldo, tema. **Sin secretos.**
- Último inventario del bucket para la estimación de costes (DataStore): cantidades y tamaños por clase de almacenamiento y por mes de subida, y el bucket que ya está en los ajustes. Sin credenciales, claves de objeto ni nombres de archivo; se borra al desconectar. Los precios van en la app y **no se consulta ningún servicio de precios** ([cost-estimate.md](cost-estimate.md)).
- Credenciales: solo cifradas (archivo + Keystore).
- Álbumes privados: `filesDir/vault/*.v|.t` (fotos y miniaturas cifradas con nombres aleatorios) y tablas `private_*` (clave del vault envuelta por el Keystore, nombres de álbum y metadatos cifrados). Sin texto claro: hay una prueba que lo comprueba en todas las columnas y archivos.
- Búsqueda por contenido (opcional): el modelo (≈ 150 MB, `filesDir/models`) y un vector de 512 valores por foto (`semantic_embeddings`). Los vectores describen el contenido de las fotos: se pueden borrar desde la propia pantalla y se excluyen de las copias de Android.
- **Personas (opcional):** por cada rostro, su posición y una huella de 128 números **sellada con AES-256-GCM** (`faces.sealed`), y el centro de cada grupo (`people.centroidSealed`); nombres, correcciones y reglas en claro en la base privada. La clave de datos está cifrada por el Keystore en el directorio *no-backup*. No se guardan recortes de caras. Detalle y límites en [face-grouping.md](face-grouping.md#privacidad-y-datos).
- Copias de seguridad de Android desactivadas (`allowBackup=false`, reglas de extracción vacías): ni el índice ni las credenciales viajan a la nube de Google.

**En S3 (tu bucket)**

- La foto original, con clave `{prefijo}/photos/{assetId}/{versionId}/{nombreSanitizado}`.
- Su miniatura JPEG (≤ 720 px) en `{prefijo}/.s3vault/thumbs/{versionId}.jpg`: **es la foto reducida**, con la misma exposición que el original (mismo bucket, mismo cifrado en reposo, mismos permisos de lectura). Se puede desactivar en Ajustes generales.
- Metadatos del objeto: `original-name` (URL-encoded), `asset-id`, `sha256-hex`, `captured-at-ms`, tipo de contenido.
- Un objeto de prueba `.s3vault/connection-check.txt` si usaste «Probar permisos».

**Visible para quien tenga permisos sobre el bucket**: todo lo anterior (contenido de las fotos, nombres de archivo, fechas de captura, hashes, y los tiempos de subida). Con SSE-S3/SSE-KMS los datos están cifrados *en reposo* en AWS, pero cualquier identidad autorizada los lee en claro. **No es cifrado de extremo a extremo.**

**Datos faciales en S3:** ninguno. Ni huellas, ni recortes, ni grupos, ni nombres; el respaldo por persona solo decide qué fotos (enteras, como siempre) entran en la cola.

**Para el desarrollador de la app**: nada. No hay servidor, cuenta, analítica ni telemetría; ningún dato sale del teléfono salvo hacia tu bucket.

## Reglas de eliminación

Ver también [cloud-previews.md](cloud-previews.md#borrado-reglas-de-seguridad).

- «Eliminar del dispositivo» **no** borra su copia en S3 (la fila pasa a `REMOTE_ONLY` si había copia verificada; si nunca se respaldó, la foto se pierde y la confirmación lo advierte). Quitar una foto del teléfono por otros medios tampoco toca S3.
- «Eliminar de S3» **no** toca el archivo local: la foto sigue en el teléfono, queda marcada y el respaldo automático no la vuelve a subir.
- No existe «eliminar de ambos sitios» en un solo paso.
- Quitar una foto de un álbum o eliminar un álbum solo borra la agrupación.
- Quitar una foto de una persona, combinar, separar, ocultar o eliminar un grupo, quitar una regla de respaldo y **borrar el índice facial** solo cambian el índice facial: ninguna foto, copia de seguridad ni objeto de S3 se borra, y lo ya puesto en la cola sigue en ella salvo que se use «Deshacer».
- Cerrar sesión/desconectar y desinstalar no ordenan borrado remoto (desconectar sí vacía la caché de vistas previas).
- Con acceso parcial a fotos (Android 14+), la ausencia de una foto en la lectura **nunca** se interpreta como borrado.
- Restaurar nunca sobrescribe: MediaStore asigna un nombre nuevo si hay colisión, y la app pide confirmación cuando ya existe una copia local del mismo contenido.

## Revisión de seguridad realizada

Comprobado en código y pruebas: no hay credenciales embebidas ni claves en el repositorio; sin `HostnameVerifier`/`TrustManager` personalizados; HTTPS-only en release; `allowBackup=false`; ningún `Log` directo con datos de usuario (todo por `SafeLog`); secretos solo en `SecretStore`; sin permisos de internet más allá de `INTERNET`/`ACCESS_NETWORK_STATE`; permisos de medios mínimos (`READ_MEDIA_*`, `READ_EXTERNAL_STORAGE` hasta API 32), sin acceso a toda la unidad; los `Intent` de compartir usan permisos de URI temporales.

Pendiente o fuera de alcance: bloqueo biométrico/PIN **de toda la app** (los álbumes privados sí se protegen; el resto de la galería y las credenciales guardadas siguen abiertas para quien tenga el teléfono desbloqueado), análisis de dependencias con herramienta externa, pruebas de penetración, y verificación en dispositivo real con Play Integrity/bootloader desbloqueado.

## Revisión previa a la publicación (25 de octubre de 2026, versión 0.4.1)

Qué se comprobó, cómo y con qué resultado. «Comprobado» significa que se ejecutó o se leyó el código; no es una auditoría independiente.

| Comprobación | Cómo | Resultado |
|---|---|---|
| Secretos en el repositorio | `git ls-files` y `git grep` de patrones de claves AWS (`AKIA…`, `ASIA…`, bloques de clave privada) en todo lo versionado | Ninguno. Solo aparece la clave de ejemplo de la documentación de AWS (`AKIAIOSFODNN7EXAMPLE`) en pruebas. `keystore.properties`, `*.jks` y `*.keystore` están en `.gitignore`; `policy.json` y `lifecycle.json` (datos de la cuenta del propietario) están excluidos localmente |
| Firma | `bundleRelease`/`assembleRelease` con `keystore.properties`, con variables de entorno y sin ninguna de las dos | Firmado en los dos primeros casos (verificado con `jarsigner`/`apksigner`); sin firmar, sin fallar, en el tercero. Ningún valor secreto en el repositorio |
| Dependencias vulnerables | Lista real del classpath de release (254 artefactos) consultada en la base pública OSV (solo se envían coordenadas Maven) | **0 avisos conocidos** para esas versiones. Limitación: OSV solo conoce lo publicado; no sustituye a un análisis comercial |
| Rastreadores | Nombres de las 254 dependencias | Ninguna de analítica, publicidad, informes de fallos ni mensajería |
| Manifiesto final | Manifiesto fusionado de release | Permisos mínimos y justificados ([play-store/declarations.md](play-store/declarations.md)); `allowBackup=false`; sin `debuggable`; solo la actividad principal y componentes de bibliotecas exportados |
| TLS | `network_security_config` | HTTPS obligatorio en release; solo `src/debug` permite HTTP a `localhost` |
| Objetos públicos | Búsqueda de ACL públicas y políticas con principal `*` en el código | Ninguna: la app no establece ACL ni políticas |
| Registros | `SafeLog`/`Redactor`, prueba `NoSecretsInLogsTest`, búsqueda de `Log.`/`println` directos | Solo `SafeLog` registra, con credenciales y tokens redactados |
| Operaciones destructivas | Código y pruebas de borrado | Siempre con confirmación; borrar local y borrar en S3 son acciones separadas; la auditoría y las herramientas nuevas solo leen en S3 |
| Mensajes de error | Revisión del catálogo de errores | Reescritos varios mensajes técnicos («SlowDown», «multipart», «checksum») en lenguaje comprensible; ninguno incluye secretos |

### Riesgos residuales (no eliminados)

- **Dispositivo comprometido o con acceso de superusuario:** el Keystore no impide usar las claves; ni los secretos ni los álbumes privados están protegidos frente a malware que corra con los permisos de la app o frente a root.
- **La galería no se oculta en «recientes» ni en capturas de pantalla:** solo las pantallas con secretos y los álbumes privados bloquean capturas. Quien tenga el móvil desbloqueado puede abrir la galería y, con ella, usar las credenciales guardadas (no hay bloqueo de toda la app).
- **Los originales se suben tal cual**, con sus metadatos EXIF (incluida la ubicación GPS). La app no los elimina antes de subir.
- **Credenciales estáticas:** son de larga duración; la protección real es una política IAM de mínimo privilegio y poder revocarlas.
- **Las copias en S3 no están cifradas de extremo a extremo:** dependen de la configuración y los permisos del bucket del usuario.
- **Álbumes privados:** si se pierde la clave del dispositivo, no hay recuperación (por diseño).
- **Un tercero con acceso a la cuenta de AWS del usuario** puede leer o borrar lo que su política permita, con o sin la app.
- **Descarga opcional del modelo:** el contenido está fijado por tamaño y SHA-256, pero se descarga de `huggingface.co`; si ese servicio cambiara de URL, la descarga falla (no hay descarga insegura de reemplazo).
- **Personas:** la huella facial es un dato biométrico; el cifrado la protege de una copia de la base de datos, **no** de código que corra dentro de la app ni de un móvil con root, y la clave no exige desbloqueo (el análisis corre en segundo plano). Los nombres de las personas no están cifrados. Los grupos pueden equivocarse (gemelos, parientes): una regla de respaldo mal confirmada puede subir a S3 la foto de otra persona; por eso lo dudoso se pregunta y el usuario confirma los grupos.
- **No hay prueba de penetración ni revisión independiente.** No debe interpretarse la ausencia de hallazgos como garantía.
