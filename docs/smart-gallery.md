# Galería inteligente: organización, búsqueda y filtros

Las dos galerías (la del dispositivo y la de S3) comparten la misma interfaz y la misma lógica de consulta: secciones por fecha de captura, búsqueda, filtros, orden, salto a una fecha, densidad de cuadrícula y selección. Lo que cambia es **de dónde salen los datos** y, por tanto, lo que se puede afirmar sobre ellos.

## Fechas: qué es una «fecha de captura»

Cada elemento guarda tres cosas: la marca de tiempo (milisegundos UTC), de **dónde sale** (`DateSource`) y la clave de día `yyyyMMdd` calculada en la zona horaria del dispositivo.

| Origen | Significado | Dónde |
|---|---|---|
| `CAPTURE` | Fecha de captura real (EXIF/`DATE_TAKEN` en el dispositivo; metadato `captured-at-ms` o la copia local en S3) | ambas |
| `FILENAME` | Fecha escrita en el nombre (`IMG_20240312_101530.jpg`, `Screenshot_2024-03-12…`). Fiable para el día, no prueba de captura | ambas |
| `FALLBACK` | Ninguna información de captura: las fechas del archivo (local) o la **hora de subida** (S3) | ambas |

Reglas:

- Una fecha solo es creíble entre 1990-01-01 y dentro de 2 días (desfase de relojes). Valores a cero, futuros o absurdos se descartan, nunca se muestran como «1970».
- **Local:** las fotos `FALLBACK` siguen en la línea de tiempo por la fecha del archivo, marcadas como estimadas y filtrables con «Sin fecha de captura» (Filtros → Fecha estimada).
- **S3:** la hora de subida **nunca** se presenta como fecha de captura (una biblioteca migrada en una tarde tendría el mismo día para todas). Esos objetos van a una sección final «Sin fecha de captura», ordenados por fecha de subida, y quedan fuera de los filtros de fecha y de las búsquedas por año o mes.
- **Zonas horarias:** se guarda siempre UTC; el día se agrupa en la zona actual del dispositivo, con horario de verano correcto. Si la zona del dispositivo cambia, las claves de día se recalculan una vez en el siguiente escaneo (`DayKeyMaintainer`). MediaStore no expone el desfase horario de la captura, así que una foto tomada en otra zona se agrupa según la zona actual (limitación conocida).

### Cómo obtiene S3 una fecha de captura sin pedirla

1. **Gratis, al subir:** la copia local aporta su fecha (`recordUpload`) y, en cada listado, `applyLocalCaptureDates` copia a los objetos de S3 la fecha de captura de su foto local ya respaldada.
2. **Gratis, por el nombre:** si el nombre lleva fecha, `FILENAME`.
3. **Gratis, al abrir los metadatos de un objeto:** el `HEAD` de la hoja de detalles enseña la fecha a la caché.
4. **Opt-in, con coste explícito:** «Completar fechas» lee `captured-at-ms` con un `HEAD` por objeto (0,0004 US$ por cada 1000), **una sola vez por objeto** (`metaChecked`, también si el objeto no tiene metadato), en segundo plano, con 4 peticiones en paralelo, reanudable. La tarjeta dice cuántas peticiones y cuánto cuesta antes de empezar. Nunca se lanza sola.

## Qué se puede buscar y dónde

| Campo | Galería local | Galería S3 |
|---|---|---|
| Nombre de archivo (subcadena, sin distinguir mayúsculas) | sí | sí |
| Carpeta (nombre del álbum de MediaStore) | sí | **no** (S3 no tiene carpetas de origen) |
| Año, mes, día, `2023-03-15`, `15/03/2023`, «15 de marzo» | sí, por fecha de captura | sí, **solo objetos con fecha de captura** |
| Tipo (`foto`, `vídeo`) | sí | sí, deducido de la extensión |
| Contenido de la foto, personas, lugares | no | no |

La búsqueda se interpreta en español y se enseña como chips quitables bajo el campo («Marzo», «2023», «IMG»), de modo que se ve cómo se entendió el texto y se puede quitar solo una parte.

### La búsqueda en S3 no es global

La galería de S3 se alimenta de **la lista descargada** (la caché local del último `ListObjectsV2`). Buscar, filtrar y ordenar se resuelve contra esa caché en Room: **no envía ninguna petición a S3** (hay una prueba instrumentada que cuenta las peticiones recibidas por el servidor simulado antes y después). Por eso:

- La pantalla muestra siempre la antigüedad de la lista («Lista descargada hace 5 min · 12 034 objetos») y, mientras se busca o filtra, «Busca solo en los N objetos de la lista descargada. No consulta S3».
- Un objeto subido desde otro dispositivo no aparece hasta actualizar el listado (botón de la barra superior); un «Sin resultados» lo explica.
- Si el último listado no terminó (red, credenciales, cancelación) se avisa de que la lista puede estar incompleta, y **no se purga nada** de la caché hasta que un listado llegue al final.
- Sin conexión sigue siendo posible explorar la lista descargada; actualizar falla con un mensaje y reintento, sin perder lo mostrado.

## Filtros y orden

Solo se ofrecen los que el metadato disponible responde con fiabilidad:

| Filtro | Local | S3 |
|---|---|---|
| Fecha de captura (atajos «Últimos 30 días», «Este año», «Año pasado» o rango libre) | sí | sí (excluye objetos sin fecha de captura; la hoja lo dice) |
| Tipo: fotos / vídeos | sí | sí (por extensión; lo dice la hoja) |
| Respaldo: respaldadas, sin respaldar, en curso, con error | sí | — |
| Disponibilidad: solo en S3 / también en el dispositivo | — | sí |
| Carpeta | sí | — |
| Sin fecha de captura | sí | (sección propia) |

El botón de la hoja anuncia **cuántos resultados** daría la combinación antes de aplicarla, de modo que nunca se llega a una pantalla vacía sin saberlo. Los filtros activos son chips quitables de uno en uno, con «Borrar todo» y «Restablecer». Los filtros y la búsqueda no se recuerdan entre sesiones (abrir la app muestra todo); sí se recuerdan el orden, la agrupación, la densidad y el modo lista/cuadrícula.

Orden: fecha de captura, nombre y tamaño en ambas; fecha de modificación (local); fecha de subida (S3). Cada campo en ambos sentidos. Las secciones por fecha solo existen cuando el orden es por la fecha a la que se refieren; con otro orden la lista es plana.

## Organización de la interfaz

- **Secciones** por día, mes o año, con su recuento («Miércoles 15 mar 2023 · 3 elementos»), y una sección final «Sin fecha de captura» donde corresponde.
- **Ir a una fecha:** hoja con años y meses (con recuentos de lo que existe), «Todo 2023» y un selector de día exacto; si ese día no tiene fotos se va al más cercano anterior y se avisa. Además, un **desplazador rápido** en el borde con la fecha de la sección bajo el dedo.
- **Densidad** de la cuadrícula (cómoda, media, compacta) y, en S3, **modo lista** (miniatura, nombre, tamaño, fecha, dónde está).
- **Selección:** mantener pulsado; seleccionar todo lo que coincide con la búsqueda actual o una sección entera desde su encabezado. Acciones: respaldar, añadir a álbum, compartir, eliminar (local); restaurar, eliminar de S3 (S3). Las reglas de borrado no cambian (ver [cloud-previews.md](cloud-previews.md)).
- **Estado de cada elemento:** en la galería local, el indicador de respaldo de siempre; en S3, una insignia «solo en S3» / «también en el dispositivo» y, al restaurar, su progreso.
- **Estados:** esqueletos de carga (nunca huecos en blanco), sin resultados con salida en un toque, error de listado con reintento, sin conexión, sin configurar, y avisos de lista incompleta o antigua.
- **Accesibilidad:** descripciones de contenido por celda («nombre, estado»), encabezados semánticos, objetivos de 48 dp, estados seleccionados anunciados, y el desplazador rápido es opcional (todo se alcanza también desde «Ir a una fecha»).

## Rendimiento y estructura

- Room v3 con índices compuestos `(present, dateTakenMs)`, `(present, dayKey)`, `(remoteKey)`, y en S3 `(dayKey, effectiveDateMs)` y `(uploadDayKey)`. Migración 2→3 probada con el esquema exportado.
- **Nada del catálogo entero en memoria.** Una consulta SQL parametrizada (todos los valores enlazados, nunca concatenados) devuelve **una fila por día** (`DayCount`): de ahí salen los encabezados, los recuentos por mes y año y las posiciones del salto, con aritmética sobre unos miles de filas en lugar de 100 000 elementos. Las fotos se piden por páginas de 96 a medida que la cuadrícula las necesita; `PagedWindow` conserva como máximo 20 páginas (LRU) y se invalida en el sitio cuando Room notifica un cambio. No se usa Paging 3 porque el salto a una fecha y los recuentos de sección necesitan esa aritmética.
- El visor recorre la **misma consulta** que la cuadrícula (mismo orden y filtros), no una copia de la lista.
- Mediciones en el emulador con 20 000 elementos en cada galería (`LibraryPerformanceTest`, registro `LibraryPerf`): primera página, página profunda, recuentos por día, búsquedas y filtros se resuelven en decenas de milisegundos; el plan de la consulta por defecto usa el índice y no ordena la tabla.

## Coste en AWS

| Acción | Peticiones a S3 |
|---|---|
| Abrir la pestaña S3, buscar, filtrar, ordenar, cambiar de vista, saltar a una fecha | **0** (solo Room) |
| Actualizar el listado | 1 `ListObjectsV2` por cada 1000 objetos, volcado a Room página a página (nada se acumula en memoria) |
| Respaldo automático/manual | Reutiliza el listado si tiene menos de 10 minutos (`refreshIfStale`); las subidas se registran al instante (`recordUpload`) sin volver a listar |
| Fechas de objetos ajenos | 0 por defecto; con «Completar fechas», 1 `HEAD` por objeto, una vez en la vida del objeto |
| Miniaturas y vista previa | Como antes: miniatura cacheada en disco; el original no se pide |

El mismo listado alimenta también el inventario de la [estimación de costes](cost-estimate.md), agregado página a página y guardado **solo si el listado llegó al final**.

## Límites conocidos

- La fecha de captura de objetos subidos por otras herramientas no se conoce hasta que se lee su metadato (opt-in) o aparece la copia local; hasta entonces están en «Sin fecha de captura».
- Sin desfase horario de captura (MediaStore no lo da): el día es el de la zona actual del dispositivo.
- La búsqueda en S3 es sobre la lista descargada, no sobre el bucket; no hay búsqueda por contenido, personas ni lugares.
- Videos en S3: el tipo se deduce de la extensión; un objeto con una extensión desconocida no entra en ningún filtro de tipo.
