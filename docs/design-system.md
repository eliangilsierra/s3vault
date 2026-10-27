# Sistema de diseño

Dirección: **«cuarto oscuro editorial»**. Una utilidad fotográfica sobria en la que las fotos son lo único con color vivo.
La interfaz es neutra y cálida, usa un único acento verde azulado y reserva el color de estado para comunicar significado.

## Cómo se eligió

La skill `ui-ux-pro-max` (en `.claude/skills/`) se ejecutó con la consulta «photo gallery cloud backup private editorial calm premium».
Se aplicó con criterio, no al pie de la letra:

| De la skill | Decisión |
|---|---|
| Estilo *Swiss Modernism 2.0* (rejilla, espaciado matemático, un solo acento) | Base de la composición: cuadrícula regular, escala de espaciado fija, un acento. |
| *Dark Mode (OLED)*: negro profundo, contraste alto | Modo oscuro casi negro (`#0E0F0E`) para que las fotos destaquen. |
| Tipografía de una sola familia con pesos (*Minimal Swiss*) | Se usa la fuente del sistema con una escala ajustada. No se añade ninguna dependencia de fuentes. |
| Reglas de UX: objetivos ≥ 44 px, separación ≥ 8 px, animaciones 150–300 ms, `reduced-motion`, esqueletos de carga, color nunca como único indicador | Aplicadas en todos los componentes (48 dp, 160–220 ms, `rememberReducedMotion`, `GridSkeleton`, icono + texto en cada estado). |
| Resultado del generador: *App Store Style Landing* y *Vibrant & Block-based* | **Descartado.** Son patrones de páginas de marketing y chocan con un producto sobrio. |

## Tokens (`presentation/theme/Theme.kt`)

- **Color.** Esquemas claro y oscuro fijos, sin color dinámico, para que la identidad sea la misma en todos los móviles.
  Superficies en cinco niveles tonales (`surfaceContainerLowest … Highest`) en lugar de bordes y sombras.
  Estados semánticos: `success` y `warning` (en `MaterialTheme.status`), `error` e info (acento) del esquema.
  `PhotoOverlay` agrupa los colores de lo que se dibuja *encima* de una foto (siempre sobre un velo oscuro).
- **Tipografía.** Escala de 12 estilos con interlineado y espaciado propios; `MaterialTheme.technical` (monoespaciada) para claves, huellas y errores.
- **Espaciado (`Space`).** 2 · 4 · 8 · 12 · 16 · 24 · 32 · 48 dp; margen de pantalla 16 dp; objetivo táctil 48 dp; ancho máximo de contenido 640 dp.
- **Formas.** 4 · 8 · 12 · 16 · 24 dp. Las fotos de la cuadrícula son cuadradas y sin esquinas; las superficies agrupadas usan 16 dp.

## Componentes (`presentation/ui/Components.kt`)

`AppTopBar`, `BackButton`, `PrimaryButton`, `SecondaryButton`, `QuietButton`, `ChoiceChip`, `EmptyState`, `StatusBanner`, `SectionHeader`,
`SlimProgress`, `skeleton()`, `SettingsGroup`, `SettingsRow`, `SwitchRow`, `LabeledValue`, `ConfirmDialog`, `rememberReducedMotion`.
En `presentation/Common.kt`: `MediaGrid`, `SyncBadge`, `SyncPill`, `GridSkeleton`, `LoadingBox`.

Cada estado vacío, de permiso o de error usa `EmptyState` y responde a tres preguntas: qué ha pasado (título), qué significa (cuerpo) y qué puede hacer el usuario (acciones).

## Decisiones por pantalla

- **Galería.** Cuadrícula adaptable (mínimo 112 dp: 3 columnas en un móvil, 8 en una tableta) con huecos de 2 dp, cabeceras de día discretas con recuento,
  esqueleto con la misma forma que la cuadrícula y selección con animación (la foto se encoge sobre un fondo tintado y aparece una marca).
  Una línea de respaldo sobre la cuadrícula aparece **solo** si hay elementos en cola, subiendo o con error, y se calcula con el estado de sincronización ya confirmado.
- **Visor.** Pantalla completa inmersiva: los controles y las barras del sistema se ocultan con un toque. Los metadatos van en una hoja inferior, no sobre la foto.
  «Respaldar» devuelve un mensaje honesto (en cola, ya respaldada, S3 sin configurar).
- **Respaldo.** Una tarjeta resume «X de Y respaldadas y verificadas» con una barra real (respaldadas / total), el estado actual y los contadores.
  Los errores se muestran en lenguaje llano con los detalles técnicos a un toque.
- **Ajustes y AWS.** Secciones con grupos, formulario dividido en *Destino*, *Método de autenticación* y *Conexión*, términos de AWS explicados en contexto,
  y avisos que reflejan que **la app no renueva sola las credenciales temporales**.

## Selección, vista previa y borrado

Se aplicó `ui-ux-pro-max` (reglas de UX: objetivos ≥ 44 px, estados de carga con esqueletos, error cerca del problema, confirmación de acciones destructivas, color nunca como único indicador) y se descartaron de nuevo los patrones de marketing del generador.

- **Lenguaje visual de las dos ubicaciones.** Un icono de **móvil** significa siempre «este dispositivo» y una **nube** siempre «Amazon S3», en la hoja de elección, en los diálogos y en las insignias. Los textos nombran la ubicación (no «borrar» a secas).
- **Barra contextual de selección.** Título con el recuento y subtítulo con dónde están las copias («Todas con copia en S3», «2 con copia en S3 · 1 sin copia»). Acciones frecuentes visibles (seleccionar todo, respaldar, eliminar en rojo); las demás en el menú «Más acciones». Retroalimentación háptica al iniciar la selección y marca animada por celda; en la lista de S3 la misma marca sobre la miniatura.
- **Eliminar = elegir, confirmar, ver el resultado.** Hoja «¿De dónde?» con las dos opciones (la que no aplica aparece desactivada con el motivo), confirmación específica de cada ubicación con el impacto contado (cuántas siguen en la otra ubicación y cuántas se perderían), casilla obligatoria si algo solo existe en S3, diálogo de progreso con «n de m» y «Cancelar» para S3, y resultado en una snackbar o, si hubo fallos, en un diálogo con la causa y «Reintentar».
- **Errores con salida.** Credenciales caducadas ⇒ «Ir a credenciales»; política IAM sin permiso ⇒ explica que es lo habitual y dónde activarlo; objeto ya inexistente ⇒ el listado se refresca solo.
- **Visor de fotos de S3.** Mismo lienzo inmersivo que el visor local. Una etiqueta dice qué se está viendo («Vista previa · no es el original», «Miniatura incrustada · muy baja resolución», «Sin vista previa»); los estados de carga, sin vista previa y error ocupan el lugar de la foto, con su acción (*Restaurar (tamaño)*, *Reintentar*).
- **Componentes nuevos:** `DeleteHost` (hoja, diálogos, progreso y resultado compartidos por Galería, Álbumes, visor y S3), `ZoomableBox` (gesto de zoom compartido), `RemoteThumb`, `RemoteDetailSheet`, `CloudViewerScreen`.

## Galería inteligente: búsqueda, filtros y organización

Misma estructura en las dos galerías (`presentation/library`), para que lo aprendido en una sirva en la otra.

- **Barra superior con tres modos:** normal (título, recuento «N de M» si hay filtros, buscar, ir a fecha, filtros), búsqueda (el campo ocupa la barra y dice qué se busca) y selección. El cambio entre modos es un fundido de 150 ms (0 con animaciones desactivadas).
- **Chips de lo activo:** una fila bajo la barra con cómo se entendió la búsqueda y cada filtro; cada chip se quita de uno en uno y hay «Borrar todo». Nada filtra sin que se vea.
- **Barra de vista:** segmentos Día/Mes/Año (desactivados cuando el orden no es por fecha), chip de orden con su resumen en lenguaje llano («Recientes primero») y, a la derecha, densidad y, en S3, lista/cuadrícula.
- **Hoja de filtros:** solo ofrece lo que el metadato responde; el botón principal anuncia cuántos resultados daría («Mostrar 24 resultados» o «Sin resultados con estos filtros») antes de aplicar. Notas breves donde un filtro tiene límites (S3: el tipo se deduce de la extensión; los objetos sin fecha de captura no entran en filtros de fecha).
- **Ir a una fecha:** años con meses y recuentos, «Todo 2023» y selector de día exacto; el desplazador rápido del borde muestra la fecha de la sección y solo aparece con listas largas.
- **Encabezados de sección** con nombre, recuento y, en modo selección, un control para seleccionar toda la sección; el de «Sin fecha de captura» lleva una nota («Ordenadas por fecha de subida»).
- **Alcance siempre visible en S3:** línea «Lista descargada hace 5 min · N objetos» y, al buscar o filtrar, «Busca solo en los N objetos de la lista descargada. No consulta S3.». Avisos propios para lista incompleta y para error de actualización (con la lista antigua visible). La tarjeta «Completar fechas» enseña el coste antes de pedir confirmación.
- **Componentes nuevos:** `LibraryGrid`, `SectionHeading`, `TimelineScroller`, `CellSkeleton`, `LibrarySearchBar`, `ActiveChipsRow`, `LibraryViewBar`, `LibraryFilterSheet`, `LibraryTimelineSheet`, `NoResults`.

## Panel de costes estimados

Ajustes → Almacenamiento y costes (`StorageCostScreen`). Misma estructura que el resto de Ajustes (`SettingsScaffold`, `SettingsGroup`, `StatusBanner`, `SectionHeader`).

- **Lo primero es el aviso:** «Es una estimación, no tu factura», con acceso a AWS Billing. No se puede llegar a una cifra sin verlo.
- **Tarjeta principal:** el importe mensual con `displaySmall`, el nivel de confianza (icono + palabra: *alta*, *media*, *baja*) y una línea que separa lo medido de las suposiciones. Si no hay datos suficientes se dice «No disponible» y por qué, nunca una cifra a medias.
- **Etiquetas de origen en cada cifra:** *Medido* (sale del bucket), *Estimado* (regla con margen conocido) y *Suposición* (resaltada en ámbar). Icono + palabra, nunca solo color.
- **Bloques:** frescura (inventario y precios con su antigüedad y el botón «Actualizar inventario» con lo que cuesta), uso medido, desglose mensual por clase, solicitudes del mes («lo que se sabe», fuera del total), «Qué no incluye» (solo lo que aplica a tu bucket) y confianza con sus motivos.
- **Ayuda en contexto:** el icono de ayuda de la tarjeta abre una hoja inferior con la metodología en lenguaje llano.
- **Estados:** sin S3, sin inventario, calculando, otro bucket, vacío, sin credenciales (se conserva lo último), error al actualizar (con «Reintentar» o «Ir a credenciales»), inventario o precios antiguos, cambios pendientes, región o clase sin precio.
- **Importes pequeños:** nunca se muestra «US$ 0,00» para algo que no es cero (se añaden cifras: «US$ 0,0004»); en cifras secundarias, «menos de US$ 0,01».

## Accesibilidad y movimiento

- Objetivos táctiles de 48 dp; cada celda de la cuadrícula expone nombre, tipo y estado de respaldo, y su estado de selección.
- El estado se transmite con icono y palabra, nunca solo con color.
- El texto se escala por completo, salvo en la barra de navegación inferior (límite de 1,15×) porque cinco etiquetas comparten el ancho.
- Las animaciones duran entre 120 y 220 ms y se desactivan si el usuario apagó las animaciones del sistema.
- El botón Atrás sale primero del modo selección.
- Las pantallas con secretos mantienen `FLAG_SECURE`; la política de credenciales y los textos de seguridad no cambian.

## Herramientas de la biblioteca y álbumes privados

- **Entrada única:** Galería → ⋮ → «Herramientas de la biblioteca» (también desde Ajustes). El *hub* agrupa por lo que toca cada herramienta (Respaldo, Organizar, Este dispositivo, Privacidad) con una línea que dice qué hace y qué toca; las cinco pestañas no cambian.
- **Lenguaje local/nube explícito:** «en este dispositivo», «en S3», «solo en S3», con texto además del color; los estados usan los mismos `Tone` (icono + palabras) y `StatusBanner` que el resto de la app.
- **Acciones destructivas:** siempre `ConfirmDialog` con el recuento y la consecuencia, botón destructivo en color de error y «Cancelar» a la izquierda; nada destructivo es el botón primario por defecto.
- **Pantallas privadas:** sin capturas (`FLAG_SECURE`), cuadrícula de miniaturas descifradas en memoria, visor con el mismo `ZoomableBox` y los mismos controles sobre degradado que el visor normal, y un estado «Bloqueado» sin ningún dato cuando el vault se cierra con una pantalla abierta.
- Objetivos táctiles ≥ 48 dp, `contentDescription` en cada celda (nombre de la foto) y en cada icono, selección con marca y color (no solo color), y diseños que se estrechan a `Space.maxContent` en pantallas grandes.

## Tutorial de bienvenida y ayuda (octubre de 2026)

- **Estructura del tutorial:** seis pasos (bienvenida, qué puedes hacer, cómo se guardan las fotos, acceso a las fotos, conectar con S3, primera copia). Siempre se puede **saltar** (arriba a la derecha) y **volver** (abajo); en el último paso el botón es «Ir a la galería». El paso de S3 es opcional y refleja el estado real guardado (configuración, credenciales, prueba de conexión) con palabras además de iconos.
- **Contenido solo de lo que existe:** cada frase del tutorial y de la ayuda se contrastó con el código (nombres de pestañas, botones y rutas de Ajustes). No promete cifrado de extremo a extremo, sincronización instantánea ni renovación automática de credenciales.
- **Accesibilidad:** objetivos táctiles de 48 dp o más, títulos como encabezados, filas de estado con descripción unificada («Credenciales: Hecho»), contenido desplazable (con texto al 200 % el botón principal sigue visible), movimiento reducido respetado en el cambio de página, y contraste correcto en tema claro y oscuro. Fallo encontrado y corregido: el contenedor raíz no fijaba el color del texto y los títulos salían negros sobre fondo oscuro.
- **Barras superiores:** los títulos son de una sola línea con puntos suspensivos y, en modo selección, de tamaño compacto: antes «3 seleccionadas» se cortaba a «3» junto a los cuatro iconos de acción.
- **Textos y localización:** todo el texto **nuevo** (tutorial y ayuda) y el nombre de la app están en recursos de Android (`res/values/strings.xml`), en español. Los textos **anteriores** (unas 240 líneas en 17 archivos de interfaz) siguen escritos en el código: moverlos a recursos es mecánico pero toca cientos de textos que las pruebas buscan literalmente, así que se deja como tarea aparte y **no se ha hecho**. La app solo está en español.
- **Tablets y pantallas grandes:** el contenido se limita a `Space.maxContent` (640 dp) y se centra; **no se ha probado en una tableta real**.

## Personas (octubre de 2026)

Aplicada de nuevo la skill `ui-ux-pro-max` (reglas de UX: objetivos ≥ 44 px, estados vacíos con acción, confirmación de acciones destructivas, animación mínima, color nunca como único indicador) y descartado otra vez el generador de páginas de marketing. Todo texto nuevo está en `res/values/strings_people.xml`.

- **Rostros en círculo, fotos cuadradas.** Una persona se dibuja como un recorte circular de su rostro; las fotos mantienen la celda cuadrada de la galería. El recorte se corta de la foto al dibujarlo y vive solo en memoria.
- **Siempre «estimación».** La cuadrícula y la ficha dicen que los grupos son automáticos y pueden equivocarse; un grupo sin revisar lo dice con texto («Sin revisar» / «Confirmada» con icono) y su ficha ofrece «Confirmar grupo».
- **Siete estados de la pantalla** (`PeoplePhase`, probados): sin fotos, sin empezar, modelo no disponible, índice ilegible, analizando/en espera (con progreso real), sin grupos fiables y grupos (con avisos de incompleto o actualizando).
- **Primera vez.** Un diálogo de seis puntos (qué hace, dónde ocurre, qué sale del móvil, qué se guarda, cómo se borra, hasta dónde fiarse) precede a la activación; cerrarlo no cambia nada.
- **Regla de respaldo en dos pasos.** Hoja con *qué fotos* (nuevas / también las que ya tengo, con recuento y tamaño reales y las fotos para quitar de la lista), *con qué seguridad* y un resumen de lo que pasará antes del botón «Activar». Si el grupo no está confirmado, la hoja lo pide primero. Después, un aviso con «Deshacer».
- **Revisar** separa lo que la app no sabe: posibles coincidencias (✓/✗ con descripción accesible por rostro), grupos que podrían ser uno, fotos para decidir y rostros sin grupo.
- Probado en tema oscuro y con texto al 150 % (capturas en `PeopleUiTest`); objetivos de 48 dp, cada rostro y foto con descripción, sin animaciones nuevas.
