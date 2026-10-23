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
