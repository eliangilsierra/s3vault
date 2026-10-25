# Herramientas de la biblioteca

Todas viven detrás de **Galería → ⋮ → Herramientas de la biblioteca** (y Ajustes), para que la barra inferior siga teniendo cinco pestañas. Cada herramienta dice en una línea qué toca: este dispositivo, Amazon S3, o solo datos locales. Ninguna usa servidor propio ni pide permisos IAM nuevos (ver [iam-policy.md](iam-policy.md#herramientas-de-la-biblioteca-sin-permisos-nuevos)).

Principios comunes:

- **Trabajo acotado, cancelable y persistido.** Cada ejecución larga se registra en `background_jobs` (estado, hechos/total, errores, última vez). Las ejecuciones largas se parten en tandas con WorkManager (`APPEND_OR_REPLACE`, trabajo único) y un proceso que muere deja la ejecución marcada como interrumpida, no como «en curso» para siempre.
- **Nunca se afirma lo que no se comprobó.** «Falta», «duplicado» o «verificado» solo salen de una comprobación completa; un fallo, un listado incompleto o la falta de red dan «desconocido».
- **Local y nube, siempre rotulados** («en este dispositivo», «en S3»).
- Español, objetivos táctiles ≥ 48 dp, tema claro/oscuro, y listas largas con `LazyColumn`/rejilla paginada (probado con cientos de elementos).

## 1. Centro de sincronización (pestaña «Respaldo»)

Sustituye a la pantalla de respaldo anterior. Muestra de un vistazo cuántas fotos están **solo en este dispositivo**, **en el dispositivo y en S3**, **solo en S3**, en cola, subiendo o con error, el resultado de la última ejecución de cada tarea (respaldo, restauración, auditoría, duplicados, búsqueda por contenido, descarga del modelo), y para cada fallo **por qué** falló y qué hacer (credenciales, red, permisos, espacio…), con reintento. Los recuentos son consultas `COUNT` agregadas en una pasada (no cargan las filas) y el estado persiste: se ve igual tras matar la app.

## 2. Auditoría del respaldo (solo lectura)

Comprueba que S3 sigue teniendo lo que respaldaste. **Nunca modifica ni borra nada en S3**; reparar (volver a subir) es una acción aparte que el usuario elige y que solo encola archivos locales.

| Nivel | Qué hace | Coste en solicitudes |
|---|---|---|
| Listado | Lista el bucket de nuevo (un listado viejo daría un informe viejo) y compara con lo respaldado: existencia, tamaño y marcador de versión (ETag como valor **opaco que solo se compara por igualdad**, nunca como MD5). | 1 `ListObjectsV2` por cada 1 000 objetos. |
| Metadatos | `HEAD` por objeto: existencia, tamaño y metadatos. | 1 por objeto (cuenta como GET). |
| Checksum | `HEAD` con el SHA-256 que S3 guarda del objeto, contra el hash registrado al respaldar. | 1 por objeto. |

- Veredictos: **Verificada**, **Falta**, **Cambió**, **Desconocida** y **Registro obsoleto**. «Falta» solo tras un listado completo y no vacío (si el listado falló o llegó cortado, es «Desconocida»).
- **No se hace `HEAD` en cada arranque**: solo cuando el usuario lo pide o según el intervalo que elija (por defecto, nunca). Antes de lanzarlo, la pantalla dice cuántas solicitudes hará y avisa de su coste.
- Funciona con acceso parcial a fotos y con credenciales temporales que caducan a mitad (se detiene con un mensaje claro y puede reanudarse).

## 3. Liberar espacio de forma segura

Quita del móvil lo que S3 **ya tiene verificado**. **Nunca toca S3.**

- Solo recomienda fotos del grupo **«Verificadas en S3»**: respaldadas y con evidencia reciente (≤ 7 días por defecto) de que el objeto sigue existiendo y coincide. «Sin verificar» se puede verificar; «Falta en S3» y «Desactualizadas» **nunca** se ofrecen.
- Reglas de seguridad que solo pueden **acortar** la lista: excluir favoritas, conservar fotos recientes (30 días), exigir un respaldo con antigüedad mínima (7 días), carpetas excluidas, vídeos, fotos en álbumes.
- **Puerta final antes de borrar**: se vuelve a comprobar en S3 (`HEAD`) cada archivo elegido; los que no pasan se descartan con el motivo y no se borran. Que una subida esté en cola **no** cuenta como respaldo.
- El borrado usa el flujo de siempre: el diálogo propio de Android (`createDeleteRequest`), confirmación explícita y recuento de lo que se liberará. Sin promesas de ahorro en AWS: borrar del móvil no cambia lo que S3 factura.

## 4. Duplicados y parecidas

- **Idénticas**: mismo SHA-256 (se calcula solo para archivos del mismo tamaño y se guarda en la caché de hash que ya usa el respaldo).
- **Parecidas**: hash perceptual (DCT 32×32) con distancia de Hamming ≤ 8 por defecto y agrupación por bandas para no comparar todas contra todas (grupos de hasta 50). Es una **sugerencia**, no una prueba, y se rotula así.
- **Nunca se borra solo.** El usuario elige qué copia conservar; «local» y «en S3» se muestran por separado; que dos archivos locales sean idénticos **no implica que apunten al mismo objeto de S3** (se comprueba por clave remota) y no se borra nada remoto salvo que el usuario lo elija expresamente en el flujo de borrado.
- Los grupos se guardan; descartar un grupo lo recuerda (con los ids de sus miembros: si aparece una copia nueva, vuelve a mostrarse).

## 5. Álbumes inteligentes

Consultas guardadas sobre el índice de Room (con sus índices): favoritas, capturas, vídeos, grandes, sin respaldar, por año… y los del usuario (filtros combinables, definición en JSON). Los recuentos de todos los álbumes salen de **una sola consulta** con sumas condicionales; el contenido se pagina, y la parte en S3 usa el listado ya cacheado (no hay solicitudes extra). Borrar un álbum inteligente solo borra la definición.

## Qué datos locales guardan

`background_jobs`, `integrity_audits`/`integrity_results`, `media_analysis` (hash perceptual), `duplicate_*`, `smart_albums`, `semantic_embeddings` ([semantic-search.md](semantic-search.md)) y `private_*` ([private-albums.md](private-albums.md)). Ninguno contiene secretos; se borran con los datos de la app y no se copian a la nube de Google (`allowBackup=false`).
