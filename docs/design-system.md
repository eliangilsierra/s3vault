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

## Accesibilidad y movimiento

- Objetivos táctiles de 48 dp; cada celda de la cuadrícula expone nombre, tipo y estado de respaldo, y su estado de selección.
- El estado se transmite con icono y palabra, nunca solo con color.
- El texto se escala por completo, salvo en la barra de navegación inferior (límite de 1,15×) porque cinco etiquetas comparten el ancho.
- Las animaciones duran entre 120 y 220 ms y se desactivan si el usuario apagó las animaciones del sistema.
- El botón Atrás sale primero del modo selección.
- Las pantallas con secretos mantienen `FLAG_SECURE`; la política de credenciales y los textos de seguridad no cambian.
