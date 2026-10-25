# Declaraciones de Play Console: borrador

> **Estado: borrador. Nada de esto se ha enviado.** Basado en el manifiesto de la versión 0.4.0 (`app/build/intermediates/merged_manifests/release`), en el código y en la política oficial vigente consultada el 25 de octubre de 2026.

## 1. Permisos del manifiesto final y su justificación

| Permiso | Origen | Justificación | ¿Requiere declaración en Play? |
|---|---|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | App | Mostrar la galería del dispositivo y respaldar fotos y vídeos | **Sí** (política de fotos y vídeos, ver 2) |
| `READ_MEDIA_VISUAL_USER_SELECTED` | App | Funcionar con el acceso parcial de Android 14+ (solo fotos elegidas) | Va con la anterior |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | App | Lo mismo en Android 10 a 12L | No |
| `INTERNET`, `ACCESS_NETWORK_STATE` | App | Hablar con el bucket de S3 y decidir cuándo respaldar | No |
| `USE_BIOMETRIC` | App | Álbumes privados: diálogo del sistema de huella o credencial | No (permiso normal) |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | **WorkManager** (biblioteca) | Reanudar el respaldo y reprogramar las tareas periódicas tras reiniciar | No. La app **no usa servicios en primer plano**: no llama a `setForeground`; el servicio de WorkManager queda sin uso |
| `com.s3vault.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX | Receptores internos no exportados | No |

No se piden: `MANAGE_EXTERNAL_STORAGE`, ubicación, cámara, micrófono, contactos, teléfono ni notificaciones. Componentes exportados: solo la actividad principal (lanzador) y los de las bibliotecas (WorkManager y ProfileInstaller, protegidos por permisos del sistema).

## 2. Declaración de permisos de fotos y vídeos (política vigente)

Según la política oficial (consultada el 25 de octubre de 2026), las apps que apuntan a Android 13 o superior solo pueden usar `READ_MEDIA_IMAGES` y `READ_MEDIA_VIDEO` si el selector de fotos del sistema no puede cubrir su **funcionalidad principal**; las apps cuya función principal gira en torno al acceso amplio a fotos y vídeos, como las **galerías**, pueden usarlos, y hay que rellenar una **declaración** en Play Console. La app ya funciona con acceso parcial (Android 14+).

**Borrador de justificación (para pegar y adaptar):**

> S3 Vault es una galería de fotos y vídeos. Su funcionalidad principal es explorar, buscar, ordenar y respaldar **toda** la biblioteca multimedia del dispositivo. Un selector de fotos no puede ofrecerlo, porque:
> 1. La galería debe mostrar y paginar continuamente la biblioteca completa (miles de elementos) con búsqueda, filtros por fecha y agrupación, sin que el usuario elija elemento a elemento.
> 2. El respaldo automático y la comprobación de qué fotos ya tienen copia verificada exigen recorrer la biblioteca completa y detectar fotos nuevas en segundo plano.
> 3. Las herramientas de duplicados y de liberar espacio comparan archivos de toda la biblioteca.
> La app no usa `MANAGE_EXTERNAL_STORAGE`, no envía fotos al desarrollador (solo al bucket de Amazon S3 que configura el usuario cuando este decide respaldar) y funciona con el acceso parcial de Android 14 (fotos seleccionadas) mostrando solo lo concedido.

**Qué debe comprobar el propietario:** que la descripción de la ficha presente la galería como función principal (está así en el borrador), y que sea cierto en el momento de enviarlo.

## 3. Otras declaraciones («Contenido de la app»)

| Sección | Respuesta propuesta | Notas |
|---|---|---|
| Política de privacidad | URL pública de [privacy-policy-es.md](privacy-policy-es.md) | **Pendiente: alojarla** (ver [owner-data.md](owner-data.md)). |
| Anuncios | **No contiene anuncios** | Sin SDK de publicidad (verificado). |
| Acceso a la app | **Hay funciones restringidas** | La galería es libre. El respaldo requiere cuenta de AWS propia: dar a Play instrucciones y, si la revisión lo pide, un **bucket de pruebas y credenciales temporales de un entorno aislado del propietario**. **No pegar credenciales en este repositorio.** |
| Público objetivo | 18 años o más (recomendado) | Requiere una cuenta de AWS; no está dirigida a niños. Decisión del propietario. |
| Clasificación de contenido (IARC) | Utilidad: sin violencia, sexo, drogas ni apuestas; **sin contenido generado por otros usuarios** ni interacción entre usuarios; **no comparte la ubicación** ni hace compras. Rellenar el cuestionario con estas respuestas | La calificación resultante la fija IARC, no este documento. |
| Seguridad de los datos | Ver [data-safety.md](data-safety.md) | |
| Apps del gobierno / financieras / de salud / de noticias / COVID-19 | No | |
| Eliminación de cuentas | No aplica: la app no tiene cuentas | |
| Servicios en primer plano | No se usan | Si Play lo pregunta por el permiso que añade WorkManager, indicar que la app no inicia servicios en primer plano. Si se prefiere quitarlo del manifiesto, es un cambio de configuración que habría que probar; no se ha hecho. |
| Tipos de dispositivo | Teléfonos y tabletas con Android 10+ y arm64 | `minSdk 29`; el paquete solo lleva `arm64-v8a`, por lo que **no** se instala en dispositivos x86 ni de 32 bits (decisión por el tamaño de ONNX Runtime; ver [semantic-search.md](../semantic-search.md#peso-del-apk)). |

## 4. Requisitos de la versión (técnico)

- `targetSdk 36`: cumple el requisito vigente (los envíos nuevos y las actualizaciones deben apuntar a Android 16, API 36, desde el 31 de agosto de 2026).
- Formato: **Android App Bundle** (`app-release.aab`); ver [release.md](../release.md).
- 64 bits: obligatorio, cumplido (`arm64-v8a`).
- Firma: **Play App Signing**; ver [release-checklist.md](release-checklist.md) (la decisión sobre la clave de firma es del propietario).
