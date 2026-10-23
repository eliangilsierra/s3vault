# S3 Vault

Galería de fotos para Android que respalda y restaura tus fotos y vídeos en **tu propio bucket privado de Amazon S3**.

La aplicación se comunica directamente con la API de Amazon S3 mediante credenciales que controla el usuario. No depende de ningún servicio intermedio: no hay backend, cuentas de usuario, suscripciones ni telemetría.

## Características

- **Galería local.** Cuadrícula agrupada por día, mes o año según la **fecha de captura** (con las fotos sin fecha señaladas, nunca con una fecha inventada), visor a pantalla completa con zoom, selección múltiple, álbumes virtuales y tema claro, oscuro o del sistema. Funciona sin conexión y sin AWS configurado.
- **Buscar, filtrar y ordenar.** En las dos galerías: búsqueda por nombre, año, mes o fecha («marzo 2023», «15/03/2023», «IMG foto»), filtros por fecha, tipo, estado de respaldo/disponibilidad y carpeta, orden por fecha, nombre o tamaño, salto a una fecha, densidad de cuadrícula y, en S3, vista de lista. Probado con 20 000 elementos por galería. La búsqueda en S3 usa **la lista ya descargada** y no envía peticiones; la pantalla lo dice. Ver [docs/smart-gallery.md](docs/smart-gallery.md).
- **Respaldo en S3.** Manual o automático, con cola persistente, reintentos con retroceso exponencial, pausa y cancelación, restricción a redes no medidas (Wi-Fi), exclusión opcional de vídeos y concurrencia configurable. Se ejecuta con WorkManager y no requiere un servicio en primer plano.
- **Integridad verificada.** Cada subida envía una suma SHA-256 que S3 valida, y un archivo solo se marca como respaldado tras comprobar con `HEAD` su tamaño y su suma. El ETag nunca se trata como MD5.
- **Biblioteca remota.** El catálogo se deriva del listado de objetos (volcado por páginas a Room), sin manifiesto. La restauración se reanuda con `Range`, se verifica contra la suma del contenido y se publica en MediaStore sin sobrescribir archivos existentes.
- **Costes estimados.** En Ajustes → Almacenamiento y costes: objetos y datos de tu bucket por clase de almacenamiento, un coste mensual aproximado con precios oficiales de lista, desglose, confianza y límites, y cada cifra etiquetada como medida, estimada o suposición. Es una estimación, no tu factura; no añade peticiones a S3 ni permisos IAM. Ver [docs/cost-estimate.md](docs/cost-estimate.md).
- **Dos métodos de autenticación.** Credenciales temporales de STS (recomendado) o claves estáticas de un usuario IAM dedicado. Los secretos se cifran con una clave de Android Keystore.
- **Ver fotos de S3 sin descargarlas.** Al respaldar se sube una miniatura de ~60 KB; la pestaña S3 muestra miniaturas y un visor con zoom que dice honestamente que es una vista previa. Sin miniatura se intenta la incrustada en el JPEG con una lectura `Range`; el original solo se descarga con *Restaurar*. Alternativas y costes en [docs/cloud-previews.md](docs/cloud-previews.md).
- **Borrado explícito y separado.** «Eliminar del dispositivo» (diálogo del sistema Android) y «Eliminar de S3» (solo con el permiso IAM que tú actives) son acciones distintas, cada una con su confirmación; nunca se borran ambas copias de un solo paso. Quitar una foto de un álbum, cerrar sesión o desinstalar nunca toca S3.

## Cómo funciona

Cada archivo respaldado se guarda con una clave estable:

```
{prefijo}/photos/{assetId}/{versionId}/{nombre}
```

`assetId` identifica al archivo y `versionId` es el SHA-256 de su contenido. Si un archivo cambia se sube un objeto nuevo bajo el mismo `assetId` y la versión anterior se conserva. La aplicación no activa el versionado del bucket ni acceso público.

El estado de cada elemento sigue una máquina de estados explícita (`LOCAL_ONLY`, `QUEUED`, `UPLOADING`, `VERIFYING`, `BACKED_UP`, `FAILED`, `REMOTE_ONLY`, `RESTORING`) persistida en Room, que es la única fuente de verdad. Los detalles están en [docs/synchronization.md](docs/synchronization.md).

## Requisitos

- JDK 17.
- Android SDK con la plataforma 36.
- Dispositivo o emulador con Android 10 (API 29) o superior.
- Una cuenta de AWS, un bucket privado de S3 y una identidad IAM con los permisos mínimos descritos en [docs/iam-policy.md](docs/iam-policy.md).

## Compilación

Define la ruta del SDK en `local.properties` (`sdk.dir=...`) o mediante la variable `ANDROID_HOME`.

```bash
./gradlew assembleDebug      # APK de depuración
./gradlew installDebug       # instala en el dispositivo o emulador conectado
./gradlew assembleRelease    # APK con R8, sin firmar
```

## Pruebas

```bash
./gradlew testDebugUnitTest            # pruebas unitarias (JVM y Robolectric)
./gradlew connectedDebugAndroidTest    # pruebas instrumentadas (requiere dispositivo o emulador)
./gradlew lintDebug
```

Las pruebas no usan recursos de AWS: el protocolo S3 se simula con un servidor local. Consulta [docs/testing.md](docs/testing.md) para el alcance y la cobertura.

## Configuración

1. Abre la aplicación y concede acceso a las fotos.
2. En **Ajustes → Amazon S3**, indica la región, el nombre del bucket y, opcionalmente, un prefijo.
3. En **Ajustes → Seguridad y credenciales**, pega credenciales temporales o introduce claves estáticas.
4. Pulsa **Probar conexión** y **Probar permisos**.
5. Selecciona fotos (mantén pulsado) y elige **Respaldar en S3**, o activa el respaldo automático en **Ajustes generales**.

La guía completa, incluida la creación del bucket y de la identidad IAM, está en [docs/aws-setup.md](docs/aws-setup.md).

## Seguridad y privacidad

- Las credenciales se almacenan cifradas con AES-256-GCM y una clave de Android Keystore. Nunca se guardan en Room, preferencias ni registros, y las copias de seguridad de Android están desactivadas.
- Las pantallas que gestionan secretos bloquean las capturas de pantalla.
- Las conexiones usan HTTPS. La aplicación no es cifrado de extremo a extremo: el cifrado en reposo es el que configure el bucket (SSE-S3 o SSE-KMS).
- Android Keystore no impide el uso de la clave en un dispositivo comprometido. Se recomienda usar credenciales de mínimo privilegio y revocarlas si se pierde el dispositivo.

El modelo de amenazas completo está en [docs/security.md](docs/security.md).

## Alcance y limitaciones

- Solo se admite Amazon S3; no hay soporte para otros proveedores ni para endpoints compatibles con S3.
- Las credenciales temporales **no se renuevan automáticamente**. La renovación requiere un proveedor de identidad externo; [docs/authentication.md](docs/authentication.md) describe las opciones evaluadas. Al caducar, la cola se conserva y se reanuda al introducir credenciales nuevas.
- No se utiliza carga multiparte: los archivos de más de 4 GiB se rechazan con un mensaje explícito.
- El borrado en S3 está **desactivado por defecto** (la política IAM recomendada lo prohíbe); se activa a propósito, ver [docs/iam-policy.md](docs/iam-policy.md#permitir-borrar-desde-la-app-opcional).
- Las fotos de S3 sin miniatura (respaldadas antes de esta función, ya borradas del teléfono y que no son un JPEG con miniatura EXIF) solo se pueden ver restaurándolas.
- La interfaz está disponible únicamente en español.
- La búsqueda y los filtros de la galería de S3 trabajan sobre la lista descargada, no sobre el bucket, y no hay búsqueda por contenido ni por lugar. Los objetos de S3 subidos por otras herramientas no tienen fecha de captura hasta que se lee su metadato (opcional, con coste indicado) y se listan aparte; MediaStore no da el desfase horario de la captura, así que el día es el de la zona actual del dispositivo.

## Costes

S3 es un servicio de pago facturado a la cuenta de AWS del usuario: almacenamiento, solicitudes `PUT`, `GET` y `LIST`, y transferencia de salida durante las restauraciones. Las vistas previas añaden ≈ 0,02 $/mes por cada 10 000 fotos. Los detalles están en [docs/aws-setup.md](docs/aws-setup.md#costes).

## Documentación

| Documento | Contenido |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Capas, flujo de datos y decisiones de dependencias. |
| [docs/synchronization.md](docs/synchronization.md) | Máquina de estados, claves, hashes, versiones, reintentos y errores. |
| [docs/aws-setup.md](docs/aws-setup.md) | Bucket, región, prefijo, identidad IAM, credenciales y costes. |
| [docs/iam-policy.md](docs/iam-policy.md) | Políticas mínimas de identidad y de bucket. |
| [docs/authentication.md](docs/authentication.md) | Credenciales temporales: opciones evaluadas y comportamiento ante la caducidad. |
| [docs/cost-estimate.md](docs/cost-estimate.md) | Estimación de costes: metodología, fuente y fecha de los precios, confianza, permisos y límites. |
| [docs/smart-gallery.md](docs/smart-gallery.md) | Fechas de captura y su origen, búsqueda y filtros (local vs S3), rendimiento y coste en AWS. |
| [docs/cloud-previews.md](docs/cloud-previews.md) | Vista previa sin descargar y borrado: alternativas, coste, límites y reglas de seguridad. |
| [docs/security.md](docs/security.md) | Modelo de amenazas y tratamiento de los datos. |
| [docs/design-system.md](docs/design-system.md) | Dirección visual, tokens, componentes y accesibilidad. |
| [docs/testing.md](docs/testing.md) | Estrategia y cobertura de pruebas. |

## Licencia

Distribuido bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE).
