# Álbumes y fotos que solo están en S3

Los álbumes son **virtuales** y viven en el teléfono (Room). Crear, renombrar o borrar un álbum nunca elimina fotos, ni del dispositivo ni de S3.

## Importar álbumes desde el bucket

Un bucket puede traer un archivo de membresía, `{prefijo}/.s3vault/albums.json` (solo lectura para la app):

```json
{"version":1,"albums":[{"name":"Viaje","items":["<sha256 en hex, 64 caracteres>", "..."]}]}
```

Cada elemento es el SHA-256 de la foto, que es también el `versionId` de su clave `photos/{assetId}/{sha256}/{nombre}`. **Álbumes → icono de nube** lo lee con un único `GET` y coloca cada foto así:

1. **En este dispositivo:** si la foto ya tiene hash conocido (respaldada o enlazada), entra como miembro local.
2. **Solo en S3:** si no, se busca en el listado en caché (pestaña S3). Si el archivo nombra fotos que el listado no conoce, la app lista el bucket **una vez** (el mismo `ListObjectsV2` de la pestaña S3: 1 solicitud por 1.000 objetos) antes de darlas por perdidas.

No hace falta respaldar nada antes de importar. Importar es idempotente: los álbumes se reutilizan por nombre, solo se añaden miembros y nada se borra.

## Cómo se ve y qué se puede hacer

- La portada de un álbum sin fotos en el teléfono es la **vista previa** de una foto de S3 (nunca el original).
- El detalle muestra «En este dispositivo» y «Solo en S3» (o una sola sección si todo está en S3). Las fotos de S3 se abren en el visor de S3 limitado al álbum.
- Selección: **Quitar del álbum** (solo la pertenencia), **Restaurar en el dispositivo** (copia nueva, nunca sobrescribe), **Eliminar fotos…** (misma confirmación que en el resto de la app).
- Si una foto de S3 aparece luego en el teléfono, su copia local ocupa su sitio y no se cuenta dos veces.
- Al eliminar una foto de S3 desde la app, desaparece de los álbumes. Si otro agente la borra, deja de mostrarse en cuanto el listado se actualiza.

## Diagnóstico

Si no se encuentra ninguna foto, el mensaje dice cuántas nombra el archivo y dónde se buscó (dispositivo y `photos/` del prefijo configurado). Causas habituales: el archivo usa otro identificador que el SHA-256 de la foto, o las fotos están bajo otro prefijo.

## Datos

Room v4: tabla `album_remote_items(albumId, versionId, addedAtMs)` con borrado en cascada. La pertenencia se guarda por hash, no por clave, y solo se muestra mientras el listado en caché tenga un objeto con esa versión.
