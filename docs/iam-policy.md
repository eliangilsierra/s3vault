# Políticas IAM y de bucket

Principio: **permisos mínimos**, limitados al ARN del bucket y a un prefijo. No hay `s3:*` ni permisos administrativos. **Borrar en S3 es opcional y viene desactivado**: la política recomendada lo prohíbe con un `Deny`, y la app solo borra objetos si tú sustituyes ese `Deny` por la política de [Permitir borrar desde la app](#permitir-borrar-desde-la-app-opcional).

Sustituye estos marcadores en todos los ejemplos:

| Marcador | Valor |
|---|---|
| `BUCKET_NAME` | Nombre de tu bucket (p. ej. `mis-fotos-2026`). |
| `PREFIX` | El prefijo configurado en la app, **sin barra final** (p. ej. `fotos-de-elian`). Si no usas prefijo, ver [Sin prefijo](#sin-prefijo). |
| `ACCOUNT_ID` | Tu ID de cuenta AWS de 12 dígitos. |

## Qué operaciones usa la app

| Operación de la app | Acción IAM | Recurso |
|---|---|---|
| Probar conexión, explorar biblioteca, deduplicar | `s3:ListBucket` | `arn:aws:s3:::BUCKET_NAME` (recurso del **bucket**), con condición `s3:prefix` |
| Subir fotos y el objeto de prueba | `s3:PutObject` | `arn:aws:s3:::BUCKET_NAME/PREFIX/*` (recurso de **objetos**) |
| Verificar (`HEAD`), restaurar, probar permisos | `s3:GetObject` | `arn:aws:s3:::BUCKET_NAME/PREFIX/*` |
| Subir y leer las miniaturas (`PREFIX/.s3vault/thumbs/…`) y la cabecera de un JPEG con `Range` | `s3:PutObject`, `s3:GetObject` | Ya cubiertos por `arn:aws:s3:::BUCKET_NAME/PREFIX/*`: **no hacen falta permisos nuevos** |
| «Eliminar de S3» (**opcional**) | `s3:DeleteObject` (también para `DeleteObjects`) | Solo `…/PREFIX/photos/*` y `…/PREFIX/.s3vault/thumbs/*` |

Distinción clave: `ListBucket` se concede sobre el ARN del *bucket*; `PutObject`/`GetObject` sobre ARN de *objetos* (`.../*`). Mezclarlos es el error de configuración más común.

## Política de identidad (adjúntala al usuario o rol IAM dedicado)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListOnlyThePhotosFolderOfThePrefix",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::BUCKET_NAME",
      "Condition": {
        "StringLike": { "s3:prefix": ["PREFIX/photos/*"] }
      }
    },
    {
      "Sid": "ReadAndWriteObjectsUnderThePrefix",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::BUCKET_NAME/PREFIX/*"
    },
    {
      "Sid": "NeverDeleteAnything",
      "Effect": "Deny",
      "Action": ["s3:DeleteObject", "s3:DeleteObjectVersion"],
      "Resource": "arn:aws:s3:::BUCKET_NAME/*"
    }
  ]
}
```

Notas:

- La app lista siempre con prefijo `PREFIX/photos/` (también «Probar conexión»), por eso la condición `PREFIX/photos/*` es suficiente y nada más del bucket es listable.
- El objeto de prueba `PREFIX/.s3vault/connection-check.txt` queda cubierto por `PREFIX/*` (Put/Get), pero no es listable: es intencional.
- El `Deny` de borrado es redundante con el `Allow` mínimo, pero protege si alguien añade después una política más amplia al mismo usuario (un `Deny` explícito siempre gana).
- **`HEAD` y 404 vs 403:** con `ListBucket` condicionado por `s3:prefix`, S3 puede responder `403` (no `404`) a un `HEAD` de un objeto inexistente. La app lo tolera: lo trata como «no está» y sube normalmente.
- Las miniaturas no se listan nunca (se derivan del `versionId`). Sin `ListBucket` sobre `.s3vault/thumbs/`, S3 responde `403` a la lectura de una miniatura que no existe; la app lo trata como «sin miniatura». Si prefieres `404`, añade a la condición de `ListBucket` el patrón `PREFIX/.s3vault/thumbs/*` (opcional).
- **SSE-KMS:** si el cifrado por defecto de tu bucket usa una clave KMS gestionada por ti, añade `kms:GenerateDataKey` y `kms:Decrypt` sobre esa clave. Con SSE-S3 (por defecto en buckets nuevos) no hace falta nada.

### Permitir borrar desde la app (opcional)

Por defecto el `Deny` de arriba hace que «Eliminar de S3» falle con «tu política IAM no permite borrar objetos» y no se pierda nada. Si quieres poder borrar desde la app, **sustituye** el statement `NeverDeleteAnything` por estos dos (un `Deny` explícito gana siempre a un `Allow`, así que el original debe desaparecer):

```json
{
  "Sid": "DeleteOnlyPhotosAndPreviewsUnderThePrefix",
  "Effect": "Allow",
  "Action": "s3:DeleteObject",
  "Resource": [
    "arn:aws:s3:::BUCKET_NAME/PREFIX/photos/*",
    "arn:aws:s3:::BUCKET_NAME/PREFIX/.s3vault/thumbs/*"
  ]
},
{
  "Sid": "NeverDeleteVersionsOrAnythingElse",
  "Effect": "Deny",
  "Action": ["s3:DeleteObjectVersion", "s3:DeleteObject"],
  "NotResource": [
    "arn:aws:s3:::BUCKET_NAME/PREFIX/photos/*",
    "arn:aws:s3:::BUCKET_NAME/PREFIX/.s3vault/thumbs/*"
  ]
}
```

Qué garantiza:

- Solo se pueden borrar fotos y miniaturas del prefijo. El manifiesto de álbumes (`.s3vault/albums.json`), el objeto de prueba y cualquier otra cosa del bucket siguen protegidos.
- No se concede `s3:DeleteObjectVersion`: con *Versioning* activado, borrar crea un *delete marker* y **los datos siguen ahí (y se facturan)** hasta que una regla de ciclo de vida expire las versiones no actuales. Con *Versioning* desactivado el objeto se elimina de verdad.
- Las peticiones `DELETE` no se facturan. Borrar un objeto en `GLACIER_IR` antes de 90 días cobra el resto del periodo mínimo (céntimos de dólar por GB).
- La aplicación impone además sus propias reglas (solo claves de `photos/` y miniaturas, lotes de 100, confirmación explícita); la política IAM es el límite real.

Si desactivas el borrado de nuevo, vuelve a poner el `Deny` original.

### Sin prefijo

Si dejas el prefijo vacío, la app trabaja en la raíz (`photos/…`). Sustituye `PREFIX/photos/*` por `photos/*` y `PREFIX/*` por `*`. **Recomendado: usa siempre un prefijo**; limita el alcance y permite compartir el bucket con otros usos.

## Política de confianza de un rol (para credenciales temporales con `assume-role`)

Rol `s3vault-session-role` con la política de identidad anterior adjunta, y esta relación de confianza:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::ACCOUNT_ID:user/TU_USUARIO_IAM_O_IDENTIDAD" },
      "Action": "sts:AssumeRole",
      "Condition": { "Bool": { "aws:MultiFactorAuthPresent": "true" } }
    }
  ]
}
```

La identidad de origen (tu usuario IAM, un rol de IAM Identity Center…) necesita `sts:AssumeRole` sobre `arn:aws:iam::ACCOUNT_ID:role/s3vault-session-role`. La condición MFA es opcional pero recomendable. Puedes recortar aún más la sesión con `--policy` (política de sesión) al llamar a `assume-role`.

## Política del bucket (opcional, defensa en profundidad)

No es necesaria para que la app funcione. Esta fuerza TLS para todo el bucket:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyInsecureTransport",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": ["arn:aws:s3:::BUCKET_NAME", "arn:aws:s3:::BUCKET_NAME/*"],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } }
    }
  ]
}
```

Mantén activado **S3 Block Public Access** (las cuatro casillas). Nunca abras el bucket al público para «arreglar» un error de autorización.

## Identidad vs bucket vs condiciones

| Mecanismo | Se adjunta a | Para qué se usa aquí |
|---|---|---|
| Política de identidad IAM | Usuario/rol | Concede `List/Put/Get` limitados al bucket y prefijo; `Deny` de borrado (o, si lo activas, `Delete` solo sobre fotos y miniaturas). |
| Política del bucket | El bucket | Opcional: endurecer (TLS obligatorio). En la **misma cuenta** basta con que una de las dos *permita* y ninguna *deniegue*; entre cuentas hacen falta las dos. |
| Política de confianza del rol | El rol | Quién puede asumirlo (`sts:AssumeRole`), con condiciones como MFA. |
| Política de sesión (`--policy`) | La sesión STS | Recorta permisos de una sesión concreta (intersección, nunca amplía). |
| Condiciones | Cualquiera | `s3:prefix` (solo para `ListBucket`), `aws:SecureTransport`, `aws:MultiFactorAuthPresent`. |

Un `Deny` explícito siempre prevalece sobre cualquier `Allow`.

## Comprobación

Con la política aplicada, en la app: **Probar conexión** (valida `ListBucket`) y **Probar permisos** (lista, escribe y lee el objeto de prueba y muestra cada acción por separado; no comprueba el borrado a propósito, para no borrar nada: ese permiso se verifica la primera vez que borras algo). Si algo falla, ver [la tabla de errores](aws-setup.md#solución-de-errores-de-autorización).
