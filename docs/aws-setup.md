# Configuración de AWS

Eres responsable de tu cuenta AWS, de crear el bucket, de los permisos y de los costes. S3 Vault **no crea ningún recurso** ni te pide credenciales administrativas. Los comandos usan la [AWS CLI v2](https://docs.aws.amazon.com/cli/); puedes hacer lo mismo en la consola web.

## 1. Crear un bucket privado

```bash
aws s3api create-bucket --bucket BUCKET_NAME --region eu-west-1 \
  --create-bucket-configuration LocationConstraint=eu-west-1
aws s3api put-public-access-block --bucket BUCKET_NAME \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

- En `us-east-1` se **omite** `--create-bucket-configuration`.
- Nombres de bucket: 3–63 caracteres, minúsculas/números/puntos/guiones, globalmente únicos. La app valida el formato antes de usarlo.
- Los buckets nuevos ya cifran por defecto con SSE-S3 y tienen Block Public Access activo; comprueba ambos.
- **No actives Versioning** salvo que lo quieras: multiplica el almacenamiento (cada versión se factura). La app no lo activa.

## 2. Elegir una región

Elige la región más cercana a ti o la que exija tu normativa. **La región de la app debe coincidir con la del bucket.** Si no la recuerdas:

```bash
aws s3api get-bucket-location --bucket BUCKET_NAME   # null = us-east-1
```

Si te equivocas, «Probar conexión» muestra «La región configurada no coincide con la del bucket» e indica la correcta cuando S3 la devuelve.

## 3. Configurar el prefijo

Un prefijo (p. ej. `fotos-de-elian`) acota todo lo que la app lee y escribe a `fotos-de-elian/...`. Recomendado: permite compartir el bucket y limitar la política IAM. La app normaliza (`/a//b` → `a/b/`), rechaza `.`/`..`, caracteres de control y comodines, y **comprueba el prefijo en la capa de S3 además de en la interfaz**.

Estructura de claves que crea la app:

```
{prefijo}/photos/{assetId}/{versionId}/{nombre}      fotos respaldadas
{prefijo}/.s3vault/connection-check.txt              objeto de prueba (~30 bytes)
{prefijo}/.s3vault/thumbs/{versionId}.jpg            miniatura (~60 KB) para ver la foto sin descargarla
{prefijo}/.s3vault/albums.json                       álbumes publicados por tools/s3vault_import.py (solo lectura)
```

Las miniaturas viven **fuera de `photos/`** a propósito: si usas una regla de ciclo de vida sobre `photos/` (p. ej. `GLACIER_IR`), las miniaturas se quedan en S3 Standard, sin cargo de recuperación. Detalles y costes en [cloud-previews.md](cloud-previews.md).

## 4. Crear una identidad IAM dedicada

Nunca uses la cuenta raíz ni un usuario administrador.

```bash
aws iam create-user --user-name s3vault-user
aws iam put-user-policy --user-name s3vault-user --policy-name s3vault-minimal --policy-document file://policy.json
```

`policy.json` es la política de [iam-policy.md](iam-policy.md) con tus valores. **No** adjuntes `AdministratorAccess`, `AmazonS3FullAccess` ni `s3:*`.

## 5. Limitar permisos a bucket y prefijo

Es exactamente lo que hace la política anterior: `ListBucket` sobre el bucket con condición `s3:prefix`; `PutObject`/`GetObject` sobre `BUCKET_NAME/PREFIX/*`; `Deny` de borrado (el borrado desde la app es opcional: ver [Permitir borrar desde la app](iam-policy.md#permitir-borrar-desde-la-app-opcional)). Explicación completa en [iam-policy.md](iam-policy.md).

## 6. Introducir credenciales estáticas (método B, avanzado)

Solo si aceptas el riesgo: una clave permanente vale hasta que la desactives.

```bash
aws iam create-access-key --user-name s3vault-user
```

En la app: **Ajustes → Seguridad y credenciales → Estáticas** → Access key ID (`AKIA…`) y Secret access key → Guardar. Se almacenan cifradas con Android Keystore (ver [security.md](security.md)). Copia el secret solo una vez y no lo guardes en notas ni mensajes. La app rechaza claves `ASIA…` en este método (son temporales).

Rotación recomendada: crea una clave nueva, pégala en la app, desactiva la antigua.

## 7. Configurar credenciales temporales (método A, recomendado) y su origen

La app necesita: **Access key ID, Secret access key, Session token y fecha de expiración**. La app no puede generarlas: tienes que obtenerlas de una identidad que ya tengas. Opciones prácticas:

```bash
# a) Asumir un rol dedicado (política de confianza en iam-policy.md); duración configurable hasta el máximo del rol
aws sts assume-role --role-arn arn:aws:iam::ACCOUNT_ID:role/s3vault-session-role \
  --role-session-name s3vault --duration-seconds 3600

# b) Sesión de un usuario IAM (opcionalmente con MFA)
aws sts get-session-token --duration-seconds 43200 \
  --serial-number arn:aws:iam::ACCOUNT_ID:mfa/TU_USUARIO --token-code 123456

# c) Exportar lo que resuelva tu perfil actual (incluido IAM Identity Center/SSO)
aws configure export-credentials --profile mi-perfil
aws configure export-credentials --profile mi-perfil --format env
```

Pega la salida completa en **Seguridad y credenciales → Temporales → «Pegar salida de la CLI»** y pulsa «Leer datos pegados»: la app reconoce el JSON de `assume-role`/`get-session-token`, el JSON de `export-credentials` y las líneas `AWS_ACCESS_KEY_ID=…` (bash, PowerShell, cmd). **La expiración es obligatoria**: si tu salida no la trae, escríbela (ISO-8601 UTC).

Limitaciones reales (detalle en [authentication.md](authentication.md)): la app **no renueva** estas credenciales; antes de que caduquen avisa («caducan pronto») y, al caducar, detiene las transferencias, **conserva la cola** y no marca nada como respaldado hasta que pegues credenciales nuevas, momento en que el trabajo se reanuda solo.

## 8. Comprobar la conexión

**Ajustes → Amazon S3**: guarda la configuración y usa:

- **Probar conexión**: un `ListObjectsV2` de 1 elemento bajo tu prefijo. Valida bucket, región y credenciales.
- **Probar permisos**: lista, **escribe y lee** `PREFIX/.s3vault/connection-check.txt` (~30 bytes) y muestra cada acción con ✓/✗. No toca tus fotos ni borra nada (este objeto de prueba permanece; es diminuto y se sobrescribe cada vez).

La app no consulta S3 periódicamente «por si acaso»: solo hay tráfico cuando pulsas probar, refrescas la biblioteca o hay respaldo pendiente.

## Solución de errores de autorización

| Mensaje en la app | Causa probable | Qué hacer |
|---|---|---|
| El bucket no existe | Nombre mal escrito, bucket en otra cuenta/borrado | Revisa el nombre (`aws s3api head-bucket`). |
| La región configurada no coincide con la del bucket | Región equivocada | Usa la región indicada o `get-bucket-location`. |
| Credenciales inválidas (`InvalidAccessKeyId` / `SignatureDoesNotMatch`) | Clave mal copiada, clave desactivada/borrada, secret incorrecto, reloj del teléfono muy desviado | Pega de nuevo; comprueba en IAM que la clave está *Active*; sincroniza la hora. |
| Credenciales caducadas (`ExpiredToken`) | La sesión STS terminó | Genera y pega credenciales nuevas. |
| Permisos insuficientes (`AccessDenied`) | Política sin la acción o fuera del prefijo; condición `s3:prefix` que no coincide; SCP/permission boundary; política de bucket con `Deny` | Compara con [iam-policy.md](iam-policy.md); usa «Probar permisos» para ver qué acción falla (List/Put/Get). |
| Error temporal de red | Sin conexión, DNS, timeout | Se reintenta con backoff exponencial. |
| AWS limitó las solicitudes (`SlowDown`) | Demasiadas solicitudes | Se reintenta más tarde; baja las «subidas simultáneas». |
| Límite o cuota de AWS | Cuota de cuenta | Revisa Service Quotas / facturación. |
| «Tu política IAM no permite borrar objetos» al usar *Eliminar de S3* | La política recomendada lleva un `Deny` de borrado (a propósito) | Si quieres borrar desde la app, aplica [Permitir borrar desde la app](iam-policy.md#permitir-borrar-desde-la-app-opcional); si no, no hagas nada: no se ha perdido nada. |

Los mensajes nunca incluyen secretos: solo la causa, el código HTTP y el código de error de S3.

## 9. Cifrado en servidor

La app usa el cifrado en reposo que tenga **tu bucket**; no implementa cifrado propio y **no es cifrado de extremo a extremo**: cualquier identidad con acceso al bucket (y, con SSE-KMS, a la clave) puede leer las fotos.

- **SSE-S3** (AES-256): por defecto en buckets nuevos. Recomendado, sin configuración adicional.
- **SSE-KMS**: opcional; exige añadir permisos KMS a la política ([iam-policy.md](iam-policy.md)). No se impone porque complica la configuración.

## 10. Revocar credenciales y eliminar el acceso

- Credenciales temporales: caducan solas; para cortar antes, revoca sesiones del rol (IAM → rol → *Revoke active sessions*).
- Claves estáticas: `aws iam update-access-key --user-name s3vault-user --access-key-id AKIA… --status Inactive` y luego `delete-access-key`.
- Eliminar el acceso por completo: `aws iam delete-user-policy …` y `aws iam delete-user …` (o borra el rol).
- En el teléfono: **Ajustes → Amazon S3 → Desconectar y eliminar credenciales locales** (borra credenciales y configuración; **no** borra objetos de S3 ni fotos). Desinstalar la app tampoco borra nada remoto.
- Perdiste el teléfono: desactiva la clave/rol **desde AWS**; el cifrado local no sustituye a la revocación.

## Costes

La app no crea infraestructura ni costes por sí misma, pero **tu cuenta paga** (consulta la [tabla de precios actual](https://aws.amazon.com/s3/pricing/), varía por región):

| Concepto | Quién lo genera |
|---|---|
| Almacenamiento (GB-mes) | Cada foto respaldada y su miniatura (~60 KB). Las versiones nuevas de fotos modificadas **se suman** (la antigua se conserva hasta que la borres tú). |
| `PUT` | Una por foto subida, una por su miniatura (opcional, desactivable en Ajustes generales) y una por «Probar permisos». |
| `GET` | Restauraciones y vistas previas (una de ~60 KB por foto vista; una con `Range` de 128 KiB si no hay miniatura). |
| `DELETE` | Gratuitas. Solo se envían si activas el borrado y lo confirmas. |
| `LIST`/`HEAD` | Un barrido de `LIST` al iniciar una tanda de respaldo y al refrescar la biblioteca (1000 objetos por solicitud); un `HEAD` por verificación. Con muchos objetos, refrescar a menudo suma. |
| Transferencia de salida | Descargas/restauraciones fuera de AWS (la entrada a S3 no se factura). |
| Versioning | Solo si **tú** lo activas; entonces cada sobrescritura/versión se factura. |
| Multipart incompleto | No aplica: esta versión no usa multipart. Aun así puedes añadir una regla de ciclo de vida que aborte cargas incompletas tras 7 días. |

La app calcula una **estimación** de estos costes en **Ajustes → Almacenamiento y costes** (no es tu factura; metodología, precios y límites en [cost-estimate.md](cost-estimate.md)). Estimación de las vistas previas (≈ 0,02 $/mes para 10 000 fotos): [cloud-previews.md](cloud-previews.md#estimación-de-costes-recurrentes).

Evita costes innecesarios: la app no vuelve a subir lo ya respaldado (índice local + hash + deduplicación por contenido), no sondea la conexión sin trabajo pendiente y permite «solo Wi-Fi» y excluir vídeos. Considera clases de almacenamiento o reglas de ciclo de vida de S3 si el volumen crece. **No hay garantía de gratuidad** (la capa gratuita de AWS es limitada y temporal).
