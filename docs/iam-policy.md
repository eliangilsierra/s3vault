# Políticas IAM y de bucket

Principio: **permisos mínimos**, limitados al ARN del bucket y a un prefijo. No hay `s3:*`, no hay permisos administrativos y **no hay permisos de borrado** (la aplicación nunca borra nada remoto).

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
- **SSE-KMS:** si el cifrado por defecto de tu bucket usa una clave KMS gestionada por ti, añade `kms:GenerateDataKey` y `kms:Decrypt` sobre esa clave. Con SSE-S3 (por defecto en buckets nuevos) no hace falta nada.

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
| Política de identidad IAM | Usuario/rol | Concede `List/Put/Get` limitados al bucket y prefijo; `Deny` de borrado. |
| Política del bucket | El bucket | Opcional: endurecer (TLS obligatorio). En la **misma cuenta** basta con que una de las dos *permita* y ninguna *deniegue*; entre cuentas hacen falta las dos. |
| Política de confianza del rol | El rol | Quién puede asumirlo (`sts:AssumeRole`), con condiciones como MFA. |
| Política de sesión (`--policy`) | La sesión STS | Recorta permisos de una sesión concreta (intersección, nunca amplía). |
| Condiciones | Cualquiera | `s3:prefix` (solo para `ListBucket`), `aws:SecureTransport`, `aws:MultiFactorAuthPresent`. |

Un `Deny` explícito siempre prevalece sobre cualquier `Allow`.

## Comprobación

Con la política aplicada, en la app: **Probar conexión** (valida `ListBucket`) y **Probar permisos** (lista, escribe y lee el objeto de prueba y muestra cada acción por separado). Si algo falla, ver [la tabla de errores](aws-setup.md#solución-de-errores-de-autorización).
