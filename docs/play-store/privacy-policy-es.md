# Política de privacidad de S3 Vault: borrador

> **Estado: borrador técnico. No es un texto legal ni está revisado por un abogado, y no está publicado.** Describe lo que la app hace según el código, el manifiesto y las dependencias de la versión 0.4.0. Los datos del responsable van entre corchetes (`[PENDIENTE: …]`) porque **no se han inventado**: ver [owner-data.md](owner-data.md). Play exige una URL pública para esta política; hay que alojarla en un sitio del propietario (no se ha creado ninguna).

**Última actualización:** [PENDIENTE: fecha de publicación]
**Responsable:** [PENDIENTE: nombre o razón social del desarrollador]
**Contacto:** [PENDIENTE: correo electrónico de privacidad y soporte]
**Domicilio (si procede):** [PENDIENTE]

## 1. En una frase

S3 Vault no tiene servidores propios, cuentas de usuario, publicidad ni analítica: **el desarrollador no recibe tus fotos, tus credenciales ni ningún dato tuyo.** Las copias de seguridad, si las activas, van directamente de tu móvil a un bucket de Amazon S3 de tu propiedad.

## 2. Qué datos trata la app y dónde

| Dato | Para qué | Dónde se queda | ¿Sale del dispositivo? |
|---|---|---|---|
| Fotos y vídeos del dispositivo | Mostrarlos en la galería | Se leen en el móvil con el permiso de fotos | No, salvo que respaldes (siguiente fila) |
| Índice local: nombre, tipo, tamaño, fechas, dimensiones, carpeta, huella SHA-256, estado de respaldo, huellas visuales para detectar duplicados | Buscar, filtrar, ordenar, evitar duplicar copias | Base de datos privada de la app | No |
| **Copias de seguridad** (solo si configuras S3 y eliges respaldar): la foto o el vídeo original, una miniatura de hasta 720 px y metadatos (nombre original, identificador interno, SHA-256, fecha de captura) | Respaldo y vista previa en S3 | **Tu bucket de Amazon S3** | **Sí, por HTTPS, solo a tu bucket.** El original se sube tal cual, **incluidos los metadatos que contenga, como la ubicación GPS de la foto** |
| Credenciales de AWS (temporales o claves estáticas) y la configuración de S3 (región, bucket, prefijo) | Autenticar las solicitudes a tu bucket | Credenciales: cifradas con una clave del Android Keystore. Configuración: preferencias privadas | No se envían al desarrollador. La app firma las solicitudes en el móvil; a AWS le llega la firma (y, con credenciales temporales, el token de sesión), como en cualquier uso de AWS |
| Álbumes privados | Proteger fotos con tu huella, PIN o patrón | Almacenamiento privado de la app, cifrado (AES-256-GCM) | No, y no se respaldan |
| Modelo de búsqueda por contenido y vectores de tus fotos (**opcional**, desactivado por defecto) | Buscar fotos por lo que se ve | En el móvil | La **descarga del modelo** (≈ 148 MiB) se hace desde `huggingface.co` solo si la activas; Hugging Face ve tu dirección IP y los datos técnicos de toda petición HTTPS. **No se envía ninguna foto ni vector** |
| Registros técnicos | Diagnóstico local | En el móvil; las credenciales y los tokens se redactan antes de registrarse | No |

## 3. Lo que la app no hace

- No hay cuentas, inicio de sesión ni identificadores publicitarios.
- No incluye publicidad, analítica, informes de fallos remotos ni rastreadores. Se comprobó la lista de dependencias de la versión de producción: no hay bibliotecas de analítica, publicidad ni informes de fallos de terceros.
- No vende ni comparte datos; no crea perfiles.
- No hace públicos tus objetos ni tu bucket.

## 4. Terceros

- **Amazon Web Services (tu cuenta):** donde guardas tus copias. Se rigen por tu relación con AWS y por sus condiciones y política de privacidad. El coste lo factura AWS a tu cuenta. El SDK de AWS añade a sus solicitudes datos técnicos habituales (versión del SDK, sistema operativo).
- **Hugging Face:** solo para la descarga opcional del modelo de búsqueda.
- **Google Play:** distribución de la app. Los datos que Google recoge sobre la instalación se rigen por su política.

## 5. Conservación y eliminación

- **En el móvil:** desinstalar la app o borrar sus datos elimina el índice, las preferencias, las credenciales cifradas, los álbumes privados y el modelo. Hay una opción de «Desconectar» que borra las credenciales y la configuración de S3 sin tocar tu bucket.
- **En S3:** las copias son tuyas y están en tu cuenta. Puedes borrarlas desde la app (si concedes ese permiso en AWS) o desde la consola de AWS. Desinstalar la app o desconectar **no** las borra.
- **En el desarrollador:** no hay datos que conservar ni que eliminar.

## 6. Seguridad

Las conexiones con tu bucket usan HTTPS. Las credenciales se guardan cifradas con una clave del Android Keystore. Los álbumes privados usan cifrado autenticado AES-256-GCM con una clave protegida por el Keystore que exige tu huella o tu PIN. **Límites:** nada de esto protege frente a un móvil con acceso de superusuario ni frente a software malicioso con acceso a la app; las copias en S3 no están cifradas de extremo a extremo (las protege la configuración de tu bucket y tus permisos de AWS); si pierdes la clave del dispositivo, los álbumes privados no se pueden recuperar. Detalles técnicos y riesgos residuales: `docs/security.md` y `docs/private-albums.md` del proyecto.

## 7. Permisos de Android

| Permiso | Para qué |
|---|---|
| Fotos y vídeos (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, y en Android 14 o superior `READ_MEDIA_VISUAL_USER_SELECTED`; `READ_EXTERNAL_STORAGE` solo hasta Android 12L) | Mostrar tu galería y respaldar las fotos que elijas. Puedes conceder acceso solo a fotos seleccionadas |
| Internet y estado de la red | Hablar con tu bucket de S3 y decidir cuándo respaldar (por ejemplo, solo con Wi-Fi) |
| Biometría (`USE_BIOMETRIC`) | Abrir los álbumes privados con huella o credencial del dispositivo |
| Ejecución en segundo plano (permisos que añade WorkManager: reiniciar tareas tras arrancar el móvil, mantener activo el procesador mientras se sube) | Reanudar el respaldo y las tareas pendientes |

La app **no** pide acceso a todos los archivos, contactos, ubicación, cámara ni micrófono.

## 8. Menores

S3 Vault no está dirigida a menores de edad ni los trata de forma específica: requiere una cuenta de AWS, que es para adultos.

## 9. Tus derechos

Como el responsable no recibe ni conserva datos tuyos, no hay datos que acceder, rectificar o eliminar en sus sistemas. Los datos están en tu dispositivo y en tu cuenta de AWS, y los controlas tú. Para cualquier duda: [PENDIENTE: correo de contacto]. [PENDIENTE: el propietario debe revisar con asesoría legal si aplica alguna otra mención según su país (por ejemplo, la base legal y la autoridad de control).]

## 10. Cambios

Si la app cambia lo que hace con los datos, esta política se actualizará antes y se indicará la fecha. La versión vigente es la publicada en [PENDIENTE: URL de la política].
