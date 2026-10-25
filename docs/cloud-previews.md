# Vista previa de fotos en S3 y borrado

Cómo se ven las fotos que están en S3 **sin descargar el original**, qué cuesta y por qué se eligió esta solución. Los permisos IAM están en [iam-policy.md](iam-policy.md).

## Qué se hizo

| Pieza | Comportamiento |
|---|---|
| Miniatura en S3 | Al respaldar una foto, la app genera en el teléfono un JPEG de ≤ 720 px (≈ 50–90 KB) y lo sube a `{prefijo}/.s3vault/thumbs/{versionId}.jpg`. Una petición `PUT` extra por foto, sin permisos IAM nuevos (ya cubiertos por `PUT/GET` sobre `{prefijo}/*`). |
| Fotos respaldadas antes | Una pasada de relleno (*backfill*) crea las miniaturas que faltan **desde la copia que sigue en el teléfono**. Se ejecuta tras cada respaldo y desde «Respaldo → Crear vistas previas». |
| Ver una foto de S3 | Orden de búsqueda, de más barato a más caro: caché en disco (0 peticiones) → miniatura de S3 (1 `GET` de ~60 KB) → miniatura EXIF incrustada en el JPEG (1 `GET` con `Range` de 128 KiB) → «Sin vista previa» con un botón **Restaurar (tamaño)** explícito. |
| Honestidad en la interfaz | El visor etiqueta lo que muestra: «Vista previa · no es el original», «Miniatura incrustada · muy baja resolución» o «Sin vista previa». El original solo se descarga con *Restaurar*. |
| Borrado local | Diálogo propio del sistema Android (`MediaStore.createDeleteRequest`, Android 11+; `RecoverableSecurityException`, Android 10). Sin permisos de almacenamiento nuevos. |
| Borrado en S3 | `DeleteObjects` (lotes de 100) con confirmación explícita; nunca borra la copia local. Necesita `s3:DeleteObject` acotado, **desactivado por defecto** en la política recomendada. |

## Comparativa de alternativas para previsualizar sin descargar

Precios de lista de `us-east-1`; ver [Supuestos](#supuestos-y-precios) para qué cifras se verificaron.

| # | Alternativa | Coste por vista | Cobertura | Complejidad | Decisión |
|---|---|---|---|---|---|
| A | Descargar el original y decodificarlo | Tamaño completo (4 MB típico). En `GLACIER_IR`: 4 MB × 0,03 $/GB = 0,00012 $ + petición | Todos los formatos | Mínima | **Descartada**: lenta, consume datos y, con la regla de ciclo de vida del usuario (`photos/` → `GLACIER_IR` a los 30 días), cuesta retrieval por cada vista. |
| B | `GET` con `Range` de los primeros KB del JPEG y extraer la miniatura EXIF | 1 `GET` de 128 KiB | Solo JPEG con miniatura EXIF (160×120). No sirve para PNG, HEIC/HEIF, WebP, capturas de pantalla, vídeos ni fotos editadas que perdieron el bloque EXIF | Baja (`ExifInterface`, sin dependencias) | **Adoptada como respaldo** para fotos sin miniatura propia. Calidad muy baja, y lo decimos en pantalla. |
| C | JPEG progresivo: decodificar solo los primeros *scans* | Fracción del archivo | Solo JPEG progresivo. Las cámaras de móvil escriben JPEG *baseline*, donde un archivo truncado se ve como una foto cortada | Alta y frágil | Descartada. |
| D | **Miniatura generada en el teléfono al respaldar y subida a S3** | 1 `PUT` al respaldar (0,005 $/1000) + 1 `GET` de ~60 KB al ver (0,0004 $/1000) | Cualquier formato que Android decodifique (JPEG, HEIC, PNG, WebP, vídeo: fotograma) | Media; sin backend | **Adoptada.** |
| E | Lambda / S3 Object Lambda / CloudFront con redimensionado | Invocación + lectura del original completo (en `GLACIER_IR`, retrieval por GB) + `GET`. Object Lambda añade 0,005 $/GB devuelto | Todos | Alta: código, roles, despliegue, API Gateway o URL de función | **Descartada.** Cuesta más por vista que D (lee el original), exige infraestructura y contradice «sin backend». Solo se justificaría para miles de fotos subidas desde clientes sin Android; no es el caso. |
| F | URL prefirmadas + `Range` desde el cliente | Igual que B | Igual que B | Más (firmar en la app) | Descartada: B ya llama a S3 con la firma normal del SDK. |

**Por qué D y no «solo B»:** B no cubre HEIC, PNG ni vídeo y su calidad (160 px) es insuficiente para mirar una foto. D da una vista de 720 px por ~60 KB y funciona con cualquier formato; B queda como red de seguridad para respaldos antiguos cuyo original ya no está en el teléfono.

### Limitaciones que hay que conocer

- **Decodificar una imagen necesita sus bytes.** Para formatos que no son JPEG con miniatura incrustada no existe una forma fiable de ver el contenido leyendo «un trozo» del original; por eso la solución es *generar* una miniatura aparte (D), no *recortar* el original.
- **Fotos sin miniatura y sin copia local:** si una foto se respaldó antes de existir esta función, ya no está en el teléfono y no es un JPEG con EXIF, solo se puede ver restaurándola. La app lo dice y muestra el tamaño de la descarga.
- **Sin cifrado de extremo a extremo:** las miniaturas están en el mismo bucket y con el mismo cifrado en reposo que las fotos; cualquiera con acceso al bucket las ve.
- **Una miniatura por contenido:** se nombra por `versionId` (SHA-256). Dos objetos con el mismo contenido comparten miniatura; al borrar uno se conserva mientras otro la use.
- **`403` en lugar de `404`:** sin `ListBucket` sobre `.s3vault/thumbs/`, S3 responde `403` a un `GET` de una clave inexistente. La app lo interpreta como «sin miniatura» (no como error) y cae al respaldo B. Un `403` real por permisos se manifestaría igual en las fotos, que sí se listan.
- **Miniatura obsoleta:** si el original se borra desde otro dispositivo, la comprobación posterior lo detecta (`404` del original) y la biblioteca se actualiza sola.

## Estimación de costes recurrentes

Escenario: **10 000 fotos**, 4 MB de media (40 GB de originales), 1 000 vistas al mes, región `us-east-1`.

| Concepto | Cálculo | Coste |
|---|---|---|
| Almacenar las miniaturas (S3 Standard) | 10 000 × 60 KB = 0,6 GB × 0,023 $/GB·mes | **≈ 0,014 $/mes** |
| Crearlas (una sola vez, incluido el relleno) | 10 000 `PUT` × 0,005 $/1000 | ≈ 0,05 $ |
| Ver 1 000 miniaturas | 1 000 `GET` × 0,0004 $/1000 = 0,0004 $; 60 MB salientes, dentro de los 100 GB/mes gratuitos | **≈ 0,0004 $/mes** |
| Respaldo B (si se usa): 1 000 lecturas de 128 KiB | Standard: 0,0004 $. Original en `GLACIER_IR`: 1 000 × 0,01 $/1000 + 0,128 GB × 0,03 $/GB ≈ 0,014 $ | ≤ 0,02 $/mes |
| **Total recurrente** | | **≈ 0,02 $/mes** (alrededor de 0,015 $ de almacenamiento) |
| Comparación: abrir 1 000 originales de 4 MB | 4 GB × 0,03 $/GB (retrieval `GLACIER_IR`) + peticiones | ≈ 0,13 $/mes y 4 GB de datos |
| Borrar | Las peticiones `DELETE` no se facturan. Borrar un objeto de `GLACIER_IR` antes de 90 días cobra el resto del mínimo (≈ 0,00003 $ para una foto de 4 MB a los 30 días) | ≈ 0 |

Con la regla de ciclo de vida del usuario (`photos/` → `GLACIER_IR` a los 30 días) las **miniaturas no entran** porque viven fuera de `photos/`. Si alguien aplicara una regla a todo el bucket, las miniaturas (< 128 KB) se facturarían como 128 KB y pagarían retrieval: no lo hagas.

### Supuestos y precios

- Tamaño de miniatura 60 KB: estimación para JPEG de 720 px con calidad 78; depende de la foto. Comprueba el tuyo con `aws s3 ls --recursive s3://BUCKET/PREFIX/.s3vault/thumbs/ --summarize`.
- **Todas las cifras de `us-east-1` están verificadas** contra la lista de precios oficial de AWS (AWS Price List Bulk API, publicada el 2026-10-15; ver [cost-estimate.md](cost-estimate.md#precios-fuente-fecha-y-actualización)): Standard 0,023 $/GB·mes, `PUT` 0,005 $/1000, `GET` 0,0004 $/1000, `GLACIER_IR` 0,004 $/GB·mes, retrieval de `GLACIER_IR` 0,03 $/GB y `GET` de `GLACIER_IR` 0,01 $/1000.
- Verificados en la página de precios y en la documentación de clases de almacenamiento: `DELETE` gratuito, 100 GB/mes de salida gratuitos y `GLACIER_IR` con mínimo de 90 días y 128 KB facturables mínimos.
- No se incluyen impuestos, otras regiones ni tarifas de salida por encima de los 100 GB/mes.

## Borrado: reglas de seguridad

1. **Dos acciones separadas.** «Del dispositivo» y «De Amazon S3» se eligen por separado y cada una tiene su confirmación. No hay una opción que borre ambas copias, y ninguna ruta de código lo hace de un solo paso.
2. **Avisos proporcionales al riesgo.** La confirmación cuenta cuántas fotos tienen copia en la otra ubicación y cuántas se perderían. Si alguna solo existe en S3, hay que marcar «Entiendo que no se podrán recuperar» antes de poder confirmar.
3. **Lo que Android confirma es lo que cuenta.** Tras el diálogo del sistema (o si se cancela), la app comprueba en MediaStore qué archivos desaparecieron de verdad y solo cambia el estado de esos.
4. **Borrar en S3 no re-sube.** Una foto cuya copia en S3 se borró a propósito queda marcada y el respaldo automático no la sube de nuevo; elegirla y pulsar «Respaldar» quita la marca.
5. **Reintentos y errores.** `DeleteObjects` es idempotente; los fallos se informan por foto, se puede reintentar solo lo fallido y una denegación IAM detiene el resto de lotes con un mensaje que explica la causa.
6. **Solo claves propias.** `S3RemoteStorage.delete` rechaza (sin enviar nada) cualquier clave fuera del prefijo, el manifiesto de álbumes, el objeto de prueba y lotes de más de 100 claves.
7. **Una restauración en curso no se puede borrar.**
