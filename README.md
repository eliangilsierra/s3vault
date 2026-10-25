# S3 Vault

Galería de fotos para Android que, si tú quieres, guarda copias de seguridad de tus fotos y vídeos en **tu propio bucket privado de Amazon S3**.

La aplicación habla directamente con la API de Amazon S3 usando credenciales que controlas tú. No hay servidores del desarrollador, cuentas de usuario, suscripciones, anuncios ni telemetría.

> **Estado:** versión 0.4.0. Todavía **no está publicada en Google Play**; los materiales para publicarla son borradores en [docs/play-store/](docs/play-store/README.md). Se reparte como APK a probadores.

## Qué puedes hacer

- **Galería del dispositivo.** Explora tus fotos y vídeos con zoom, búsqueda por nombre, carpeta o fecha, filtros, agrupación por día, mes o año, álbumes y álbumes inteligentes. Funciona sin conexión y **sin AWS**.
- **Copias en Amazon S3.** Elige fotos y respáldalas, o activa el respaldo automático. Cada archivo subido se verifica (tamaño y SHA-256) antes de marcarse como respaldado.
- **Estado de las copias.** La pestaña *Respaldo* muestra qué está pendiente, en curso o con error, y por qué.
- **Tu bucket.** La pestaña *S3* muestra lo que ya está en la nube con vistas previas y permite **restaurarlo** al dispositivo sin sobrescribir nada.
- **Eliminar con control.** «Eliminar del dispositivo» y «Eliminar de S3» son acciones distintas, siempre con confirmación; nunca se borran las dos copias de un solo paso. Borrar en S3 está desactivado hasta que concedas ese permiso en AWS.
- **Herramientas** (Galería → ⋮): auditoría del respaldo (solo lectura), liberar espacio solo con lo verificado en S3, duplicados y fotos parecidas, álbumes privados cifrados y búsqueda por contenido opcional. Ver [docs/library-tools.md](docs/library-tools.md).
- **Ayuda integrada.** Un tutorial de bienvenida la primera vez y, siempre, *Ajustes → Ayuda y tutorial*.

Lo que **no** es: no es un servicio de nube ni cifrado de extremo a extremo (lo que subes a S3 queda protegido por la configuración de tu bucket), y una foto del móvil no tiene copia en la nube hasta que la respaldas.

## Requisitos

- Android 10 (API 29) o superior, en un dispositivo de **64 bits (ARM)**. El APK de producción solo incluye `arm64-v8a`.
- Para las copias de seguridad: una **cuenta de AWS**, un **bucket privado de S3** y credenciales con permiso para listar, escribir y leer en él. La galería no los necesita.

## Primeros pasos

1. Instala la app y ábrela: un tutorial de seis pasos explica cómo funciona (puedes saltarlo y repetirlo cuando quieras).
2. Concede acceso a tus fotos (puedes darlo solo para fotos seleccionadas).
3. Para respaldar: **Ajustes → Amazon S3** (región, bucket y prefijo) y **Ajustes → Seguridad y credenciales**; después pulsa **Probar conexión** y **Probar permisos**. El tutorial lo guía y comprueba la conexión sin subir ni borrar nada.
4. Mantén pulsada una foto, selecciona las que quieras y toca **Respaldar en S3**. Sigue el avance en la pestaña *Respaldo*.

## Configurar Amazon S3

La guía completa (bucket, región, prefijo, identidad IAM) está en [docs/aws-setup.md](docs/aws-setup.md). Enlaces oficiales de AWS: [crear un bucket](https://docs.aws.amazon.com/AmazonS3/latest/userguide/creating-bucket.html), [crear un usuario IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_users_create.html), [precios de S3](https://aws.amazon.com/s3/pricing/).

**Autenticación admitida** (la app no soporta otras):

| Método | Cuándo | Importante |
|---|---|---|
| **Credenciales temporales** de AWS (recomendado) | Uso normal | **Caducan y la app no las renueva sola.** Al caducar, la cola de respaldo se conserva y se reanuda al añadir unas nuevas ([docs/authentication.md](docs/authentication.md)). |
| **Access key y secret estáticas** de un usuario IAM exclusivo | Avanzado | Credenciales de larga duración: usa una identidad solo para esta app y revócalas si pierdes el móvil. |

**Permisos IAM mínimos** (mínimo privilegio, limitados a tu bucket y prefijo): `s3:ListBucket`, `s3:PutObject` y `s3:GetObject`. Eliminar en S3 (`s3:DeleteObject`) es **opcional** y la política recomendada lo prohíbe. Ver [docs/iam-policy.md](docs/iam-policy.md). Mantén activado el bloqueo de acceso público del bucket; la app nunca lo cambia.

## Costes

S3 es un servicio de pago que **AWS factura a tu cuenta** (la app no cobra nada): almacenamiento, solicitudes (`PUT`, `GET`, `LIST`, `HEAD`) y transferencia de salida cuando restauras. En *Ajustes → Almacenamiento y costes* hay una **estimación orientativa** con precios de lista; no es tu factura y no añade solicitudes ni permisos. Las vistas previas añaden ≈ 0,02 $/mes por cada 10 000 fotos. Algunas herramientas (como la auditoría) avisan de cuántas solicitudes harán antes de empezar. Detalles en [docs/aws-setup.md](docs/aws-setup.md#costes) y [docs/cost-estimate.md](docs/cost-estimate.md).

## Seguridad y privacidad

- Las credenciales se guardan cifradas (AES-256-GCM, clave de Android Keystore); nunca en Room, preferencias ni registros, y las copias de seguridad de Android están desactivadas. Las pantallas con secretos y los álbumes privados bloquean las capturas.
- Todas las conexiones usan HTTPS. No hay analítica, publicidad ni informes de fallos de terceros.
- Los álbumes privados cifran las fotos con una clave protegida por tu huella o PIN. **Si pierdes esa clave (por ejemplo, quitando el bloqueo de pantalla), no hay recuperación.**
- Android Keystore no protege frente a un dispositivo comprometido. Riesgos residuales y modelo de amenazas: [docs/security.md](docs/security.md).

## Limitaciones conocidas

- Solo Amazon S3 (sin otros proveedores ni endpoints compatibles).
- Las credenciales temporales no se renuevan automáticamente.
- Sin carga multiparte: los archivos de más de 4 GiB se rechazan con un mensaje claro.
- Los álbumes privados solo admiten fotos y no se respaldan en S3.
- La búsqueda por contenido es opcional, descarga ≈ 148 MiB una vez, funciona mejor en inglés y hace el APK de ≈ 5,7 MB a 33,6 MiB ([docs/semantic-search.md](docs/semantic-search.md)).
- La interfaz está solo en español.
- Probado en emulador; no se ha validado con un bucket real de AWS ni en todos los fabricantes ([docs/testing.md](docs/testing.md)).

## Informar de un problema

Abre una incidencia en [github.com/eliangilsierra/s3vault/issues](https://github.com/eliangilsierra/s3vault/issues) (también desde *Ajustes → Ayuda y tutorial → Informar de un problema*). **No incluyas credenciales ni fotos privadas.**

---

## Para desarrolladores

### Compilación

JDK 17 y Android SDK con la plataforma 36; define `sdk.dir` en `local.properties` o `ANDROID_HOME`.

```bash
./gradlew assembleDebug      # APK de depuración
./gradlew installDebug       # instala en el dispositivo o emulador conectado
./gradlew bundleRelease      # paquete .aab de release (firmado si hay configuración de firma)
./gradlew assembleRelease    # APK de release con R8
```

La firma se configura con `keystore.properties` (ignorado por git) o variables de entorno; **el repositorio no contiene claves**. Ver [docs/release.md](docs/release.md).

### Pruebas

```bash
./gradlew testDebugUnitTest            # pruebas unitarias (JVM y Robolectric)
./gradlew lintDebug
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w com.s3vault.app.test/com.s3vault.app.S3VaultTestRunner   # pruebas instrumentadas
```

Las pruebas no usan recursos de AWS: el protocolo S3 se simula con un servidor local. Alcance y resultados: [docs/testing.md](docs/testing.md).

### Cómo funciona

Cada archivo respaldado se guarda como `{prefijo}/photos/{assetId}/{versionId}/{nombre}`; `versionId` es el SHA-256 del contenido. Si un archivo cambia se sube un objeto nuevo y la versión anterior se conserva. El estado de cada elemento sigue una máquina de estados persistida en Room ([docs/synchronization.md](docs/synchronization.md)).

### Documentación

| Documento | Contenido |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Capas, flujo de datos y decisiones de dependencias |
| [docs/synchronization.md](docs/synchronization.md) | Máquina de estados, claves, hashes, versiones, reintentos y errores |
| [docs/aws-setup.md](docs/aws-setup.md) | Bucket, región, prefijo, identidad IAM, credenciales y costes |
| [docs/iam-policy.md](docs/iam-policy.md) | Políticas mínimas de identidad y de bucket |
| [docs/authentication.md](docs/authentication.md) | Credenciales temporales: opciones evaluadas y caducidad |
| [docs/cost-estimate.md](docs/cost-estimate.md) | Estimación de costes: metodología, precios y límites |
| [docs/smart-gallery.md](docs/smart-gallery.md) | Fechas de captura, búsqueda y filtros, rendimiento |
| [docs/cloud-previews.md](docs/cloud-previews.md) | Vista previa sin descargar y borrado |
| [docs/library-tools.md](docs/library-tools.md) | Sincronización, auditoría, liberar espacio, duplicados, álbumes inteligentes |
| [docs/semantic-search.md](docs/semantic-search.md) | Búsqueda por contenido: modelo, privacidad, medidas y límites |
| [docs/private-albums.md](docs/private-albums.md) | Álbumes privados: cifrado, clave, bloqueo y pérdida de la clave |
| [docs/security.md](docs/security.md) | Modelo de amenazas, datos y riesgos residuales |
| [docs/design-system.md](docs/design-system.md) | Dirección visual, tokens, componentes y accesibilidad |
| [docs/testing.md](docs/testing.md) | Estrategia, cobertura y resultados de las pruebas |
| [docs/release.md](docs/release.md) | Cómo generar y verificar el paquete firmado |
| [docs/play-store/](docs/play-store/README.md) | Borradores de la ficha, política de privacidad, declaraciones y lista de publicación |

## Licencia

Distribuido bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE).
