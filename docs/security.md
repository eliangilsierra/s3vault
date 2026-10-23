# Seguridad y privacidad

## Modelo de amenazas (resumen)

| Amenaza | Mitigación | Límite |
|---|---|---|
| Otra app lee credenciales | Cifrado AES-256-GCM con clave del Keystore; archivo en almacenamiento privado sin copia de seguridad | Malware con root o ejecutándose como esta app puede usar la clave del Keystore |
| Credenciales en logs/errores/capturas | `Redactor` + `SafeLog`; mensajes de error estáticos (solo código HTTP/S3); `FLAG_SECURE` en pantallas sensibles; sin secretos en Room/DataStore | Una captura del sistema con root o ADB debug del propio usuario no se puede impedir |
| Intercepción de red | HTTPS obligatorio (`cleartextTrafficPermitted=false` en release), validación TLS estándar de Android, sin *pinning* casero ni `TrustManager` propio | `src/debug` permite HTTP a `localhost` solo para pruebas |
| Prefijo/bucket distinto al configurado | `RemoteKeys` + `S3RemoteStorage` rechazan claves fuera del prefijo y subidas fuera de `photos/` aunque la UI falle; `ListObjectsV2` siempre con prefijo; política IAM limitada | La política IAM del usuario es el límite real: configúrala (ver [iam-policy.md](iam-policy.md)) |
| Borrado accidental o malicioso | La app **no tiene** operación de borrado remoto (ni siquiera en la interfaz `RemoteStorage`); la política recomendada añade `Deny` de borrado | Quien tenga otras credenciales puede borrar desde fuera de la app |
| Corrupción en tránsito/en reposo | SHA-256 enviado y validado por S3, `HEAD` de verificación, verificación del SHA-256 en restauración | Ver [synchronization.md](synchronization.md) (ETag no se usa como MD5) |
| Pérdida del teléfono | Credenciales temporales caducan; revoca desde AWS | El cifrado local no sustituye a revocar |
| Exposición accidental del bucket | Block Public Access documentado como obligatorio; la app nunca habilita acceso público | Depende de la configuración del usuario |

## Qué datos hay dónde

**En el teléfono**

- Tus fotos y vídeos (MediaStore; la app solo los lee, y escribe restauraciones en `Pictures/S3Vault`/`Movies/S3Vault`).
- Índice local (Room): ID de MediaStore, URI, nombre, tipo, tamaño, fechas, dimensiones, carpeta, `assetId`, hash SHA-256 (solo tras haberlo necesitado), clave remota, estado, último error (redactado), álbumes virtuales, registro de transferencias (máx. 200). **Sin secretos.**
- Preferencias (DataStore): región, bucket, prefijo, método de autenticación, opciones de respaldo, tema. **Sin secretos.**
- Credenciales: solo cifradas (archivo + Keystore).
- Copias de seguridad de Android desactivadas (`allowBackup=false`, reglas de extracción vacías): ni el índice ni las credenciales viajan a la nube de Google.

**En S3 (tu bucket)**

- La foto original, con clave `{prefijo}/photos/{assetId}/{versionId}/{nombreSanitizado}`.
- Metadatos del objeto: `original-name` (URL-encoded), `asset-id`, `sha256-hex`, `captured-at-ms`, tipo de contenido.
- Un objeto de prueba `.s3vault/connection-check.txt` si usaste «Probar permisos».

**Visible para quien tenga permisos sobre el bucket**: todo lo anterior (contenido de las fotos, nombres de archivo, fechas de captura, hashes, y los tiempos de subida). Con SSE-S3/SSE-KMS los datos están cifrados *en reposo* en AWS, pero cualquier identidad autorizada los lee en claro. **No es cifrado de extremo a extremo.**

**Para el desarrollador de la app**: nada. No hay servidor, cuenta, analítica ni telemetría; ningún dato sale del teléfono salvo hacia tu bucket.

## Reglas de eliminación

- Borrar una foto del teléfono **no** borra su copia en S3 (la fila pasa a `REMOTE_ONLY` si había copia verificada; si nunca se respaldó simplemente deja de indexarse).
- Quitar una foto de un álbum o eliminar un álbum solo borra la agrupación.
- Cerrar sesión/desconectar y desinstalar no ordenan borrado remoto.
- Con acceso parcial a fotos (Android 14+), la ausencia de una foto en la lectura **nunca** se interpreta como borrado.
- Restaurar nunca sobrescribe: MediaStore asigna un nombre nuevo si hay colisión, y la app pide confirmación cuando ya existe una copia local del mismo contenido.

## Revisión de seguridad realizada

Comprobado en código y pruebas: no hay credenciales embebidas ni claves en el repositorio; sin `HostnameVerifier`/`TrustManager` personalizados; HTTPS-only en release; `allowBackup=false`; ningún `Log` directo con datos de usuario (todo por `SafeLog`); secretos solo en `SecretStore`; sin permisos de internet más allá de `INTERNET`/`ACCESS_NETWORK_STATE`; permisos de medios mínimos (`READ_MEDIA_*`, `READ_EXTERNAL_STORAGE` hasta API 32), sin acceso a toda la unidad; los `Intent` de compartir usan permisos de URI temporales.

Pendiente o fuera de alcance: bloqueo biométrico/PIN de la app (cualquiera con el teléfono desbloqueado puede abrir la galería y usar las credenciales guardadas), análisis de dependencias con herramienta externa, pruebas de penetración, y verificación en dispositivo real con Play Integrity/bootloader desbloqueado.
