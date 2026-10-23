# Almacenamiento y costes estimados

**Ajustes → Almacenamiento y costes.** Cuenta lo que tienes en S3 y estima cuánto cuesta al mes. Es una **estimación, no tu factura**: AWS Billing y Cost Explorer son la única fuente del importe real, y la pantalla lo dice arriba del todo.

## Qué se mide y qué no

| Dato | De dónde sale | Etiqueta |
|---|---|---|
| Número de objetos, bytes y **clase de almacenamiento** de cada objeto | `ListObjectsV2` de `{prefijo}photos/` (cada objeto trae tamaño, fecha de subida y clase) | Medido |
| Fecha de subida de cada objeto (para contar las subidas del mes) | El mismo listado (`LastModified`) | Estimado (mínimo) |
| Tamaño de las vistas previas | No se puede listar con la política recomendada; se supone 60 KiB por foto | Suposición |
| Solicitudes GET/HEAD, transferencia, recuperaciones, versiones, otros prefijos | **No se conocen** | No incluido |

Alcance: solo lo que hay bajo `{prefijo}photos/`. Los objetos de ese prefijo que no son fotos de la app (se cuentan aparte) también se facturan y van incluidos; lo que haya en otros prefijos del bucket no.

## Cómo se calcula

GB = GiB (2³⁰ bytes), como factura AWS, y el almacenamiento se promedia durante el mes: la cifra es «lo que costaría si el uso actual durara todo el mes».

- **S3 Standard:** tramos por volumen (primeros 50 TB, siguientes 450 TB, resto).
- **Standard-IA, One Zone-IA, Glacier Instant Retrieval:** precio plano; los objetos de menos de 128 KiB se facturan como 128 KiB.
- **Glacier Flexible Retrieval:** precio plano más 40 KiB por objeto (32 KiB al precio de la clase y 8 KiB al de Standard).
- **Intelligent-Tiering:** precio del nivel *Frequent Access* (cota superior, el listado no dice el nivel de cada objeto) más la cuota de monitorización de los objetos de 128 KiB o más. Baja la confianza a *media*.
- **Vistas previas:** `fotos × 60 KiB` al precio Standard, solo si están activadas. Es una suposición y va aparte.
- **Subidas del mes:** objetos cuya fecha de subida cae en el mes del inventario × precio PUT. Es un **mínimo** (no cuenta vistas previas, objetos ya borrados ni subidas repetidas) y no entra en el total mensual: es un gasto de este mes, no del almacenamiento.
- **Una actualización del inventario:** `ceil(objetos / 1000)` solicitudes LIST × precio PUT/LIST (se conoce el número exacto: es lo que costó el listado).

Todo lo anterior está en `domain/cost/CostEstimator.kt`, es código puro y está cubierto por pruebas con cifras comprobadas a mano.

## Nunca un total engañoso

No se muestra **ningún total** (y tampoco nivel de confianza) cuando:

1. El inventario está incompleto (el listado nunca se guarda a medias: si falla en cualquier página, se conserva el inventario completo anterior y solo se recuerda el fallo).
2. La región no está en la tabla de precios.
3. Hay una clase de almacenamiento sin precio (p. ej. **Glacier Deep Archive**: la lista oficial no trae un producto de almacenamiento para ella, solo el *staging*, así que no se inventa un precio; también `REDUCED_REDUNDANCY` y las no reconocidas).
4. Falta el precio de una clase en la región.

En esos casos se sigue mostrando lo medido (objetos, datos, clases) y se explica por qué no hay importe.

## Confianza

| Nivel | Cuándo |
|---|---|
| **Alta** | Inventario de ≤ 7 días, precios de ≤ 180 días, sin cambios pendientes, clases conocidas y sin Intelligent-Tiering |
| **Media** | Inventario de 8–30 días; precios de 181–365 días; la app registró subidas o borrados posteriores al inventario; Intelligent-Tiering; o las vistas previas (suposición) son > 10 % del importe |
| **Baja** | Inventario de > 30 días o precios de > 365 días |

Gana el nivel más bajo, y la pantalla lista los motivos. Los «cambios pendientes» salen de los eventos que la app ya registra (subidas y borrados en S3 desde el inventario); no hay ninguna consulta extra.

## Precios: fuente, fecha y actualización

- **Fuente:** [AWS Price List Bulk API](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/region_index.json) (oferta `AmazonS3`), la fuente oficial y pública de AWS: sin cuenta, sin credenciales, sin permisos. Es la más fiable: la usan las propias páginas de precios y las herramientas de AWS. Una API de pago (`pricing:GetProducts`) exigiría credenciales IAM y no aporta nada más.
- **Tabla local:** `domain/cost/S3PriceTable.kt`, generada con `tools/update_s3_prices.py` para 36 regiones. La **app no consulta ningún servicio de precios** y no envía datos del bucket a nadie (el APK solo contiene dos URLs que abre el usuario: la página de precios y la consola de Billing).
- **Fecha:** lista publicada el **2026-10-15** (versión `20261015173540`), tabla generada el 2026-10-23. Se muestra en la pantalla. A los 180 días avisa y baja la confianza; a los 365 días la confianza es baja.
- **Qué son:** precios de lista On-Demand en dólares. No incluyen descuentos, créditos, impuestos ni la capa gratuita.
- **Actualizar:** `python tools/update_s3_prices.py` (descarga los archivos oficiales) y commit. El script falla si dos productos coinciden con el mismo precio, y las pruebas comprueban que cada región esté completa y con tramos coherentes.
- **Qué no se extrae a propósito:** Deep Archive, transferencia de salida, Select, Inventory, Storage Lens y demás funciones: la app no las estima.

Cifras de `us-east-1` verificadas contra esa lista: Standard 0,023 $/GB·mes (primeros 50 TB), Standard-IA 0,0125, One Zone-IA 0,01, Glacier Instant Retrieval 0,004 (recuperación 0,03 $/GB), Glacier Flexible 0,0036, PUT/LIST 0,005 $/1000 y GET 0,0004 $/1000.

## Cuánto cuesta calcularlo

- **Peticiones:** una actualización hace `ceil(objetos / 1000)` solicitudes `ListObjectsV2` (con 50 000 fotos, 50 solicitudes ≈ 0,00025 $). **Es el mismo listado** que ya hacen la pestaña S3 y cada tanda de respaldo, y cualquiera de ellos actualiza también el inventario: el cálculo no añade peticiones por su cuenta.
- **Sin sondeo:** no hay consultas en segundo plano ni un trabajo periódico; el inventario se actualiza al pulsar el botón (o cuando esos listados ocurren), y la pantalla muestra su antigüedad.
- **Sin peticiones por objeto:** no se hace `HEAD` de nada; el tamaño, la fecha y la clase vienen en el listado.
- **Almacenamiento local:** un JSON de unos cientos de bytes en DataStore (cantidades por clase y por mes de subida y el nombre del bucket que ya está en los ajustes). Sin credenciales, claves ni nombres de archivo. Se borra al desconectar.

## Permisos IAM

**Ninguno nuevo.** Usa `s3:ListBucket` sobre `PREFIX/photos/*`, que la política recomendada ya concede ([iam-policy.md](iam-policy.md)). Con esa política no se puede listar `.s3vault/thumbs/`, por eso las vistas previas son una suposición; si quieres medirlas, tendrías que ampliar `ListBucket` a ese prefijo, y la app no lo pide ni lo necesita.

## Estados de la pantalla

Sin S3 configurado · sin inventario todavía · calculando · inventario de otro bucket (nunca se presenta como el actual) · bucket vacío (coste 0) · sin credenciales o caducadas (se muestra el último inventario) · error al actualizar (bucket inaccesible, permisos, red: se conserva el anterior y se explica) · inventario antiguo · cambios pendientes · precios antiguos · región sin precios · clase sin precio · incompleto.

## Límites

- No sabe si el bucket tiene **Versioning**: las versiones anteriores y las marcas de borrado se facturan y no salen en el listado (saberlo exige otro permiso, que no se pide).
- No calcula los cargos por **duración mínima** de almacenamiento (el listado no dice cuándo pasó cada objeto a su clase, p. ej. por una regla de ciclo de vida), solo lo explica.
- No conoce las solicitudes GET/HEAD ni la transferencia de salida reales.
- Un bucket compartido con otras cosas fuera de `{prefijo}photos/` costará más de lo que se ve.
- Los precios son un instante: AWS puede cambiarlos entre la fecha de la tabla y hoy.
- Las clases `Intelligent-Tiering` se valoran como cota superior.
