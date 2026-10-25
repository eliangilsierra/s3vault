# Ficha de Google Play: borrador en español

> **Estado: borrador para revisar. No está publicado ni enviado a revisión.** Todo lo que sigue describe lo que la app hace hoy (versión 0.4.0); si cambia el comportamiento, hay que cambiar el texto. Los límites de longitud se comprobaron con la ayuda oficial de Play Console cuando la página los indica (descripción corta: 80 caracteres; icono: 512×512 PNG de 32 bits; gráfico de funciones: 1024×500; entre 2 y 8 capturas por tipo de dispositivo). El límite del nombre (30 caracteres) y de la descripción completa (4 000) es el habitual de Play, pero **no apareció en la página consultada: confírmalo en Play Console al pegarlos.**

## Datos básicos

| Campo | Propuesta | Notas |
|---|---|---|
| Nombre de la app | **S3 Vault: galería y copias S3** (29 caracteres) | El nombre dentro de la app y del icono es «S3 Vault». |
| Idioma predeterminado | Español (España o Latinoamérica: decidir) | La app solo está en español. |
| Tipo | App · Gratis | Sin compras ni anuncios. |
| Categoría | Fotografía (alternativa: Herramientas) | Decisión del propietario. |
| Etiquetas sugeridas | Galería, Copia de seguridad, Fotos | Elegir de la lista de Play. |
| Descripción corta (≤ 80) | **Tu galería de fotos con copias de seguridad en tu propio bucket de Amazon S3.** (77 caracteres) | |

## Descripción completa (propuesta)

```
S3 Vault es una galería de fotos para Android que, si tú quieres, guarda copias de seguridad de tus fotos y vídeos en un bucket privado de Amazon S3 que configuras tú. No hay cuentas, ni servidores del desarrollador, ni anuncios: tus fotos solo viajan de tu móvil a tu propio bucket.

IMPORTANTE ANTES DE INSTALAR
Para usar las copias de seguridad necesitas tu propia cuenta de Amazon Web Services (AWS), un bucket de S3 y credenciales con permiso para listar, escribir y leer en él. AWS cobra el almacenamiento y el uso directamente a tu cuenta; S3 Vault no cobra nada. La galería funciona sin AWS.

GALERÍA
• Explora las fotos y vídeos del dispositivo, con zoom.
• Busca por nombre, carpeta, fecha o tipo; filtra y agrupa por día, mes o año.
• Álbumes dentro de la app y álbumes inteligentes (favoritas, capturas, vídeos, grandes, sin respaldar…).
• La galería muestra lo que hay en el dispositivo: una foto solo tiene copia en la nube cuando la respaldas.

COPIAS EN AMAZON S3
• Elige fotos y respáldalas, o activa el respaldo automático.
• Cada archivo subido se verifica (tamaño y SHA-256) antes de marcarse como respaldado.
• Pestaña de estado: qué está pendiente, en curso o con error, y por qué.
• Pestaña S3: mira lo que ya está en tu bucket con vistas previas y restáuralo al dispositivo (nunca sobrescribe).
• Eliminar del dispositivo y eliminar de S3 son acciones distintas y siempre piden confirmación; nunca se borran las dos copias de un solo paso. Borrar en S3 solo funciona si concedes ese permiso en AWS.

HERRAMIENTAS
• Auditoría del respaldo (solo lee): comprueba que S3 sigue teniendo lo que respaldaste.
• Liberar espacio: solo ofrece fotos verificadas en S3 y las vuelve a comprobar antes de borrarlas del móvil.
• Duplicados y fotos parecidas, para elegir cuál conservar.
• Álbumes privados: copias cifradas que se abren con tu huella o PIN. Aviso: si pierdes la clave del dispositivo (por ejemplo, quitando el bloqueo de pantalla), no hay forma de recuperarlas.
• Búsqueda por contenido (opcional): encuentra fotos por lo que se ve, con un modelo que se ejecuta en tu móvil. Requiere una descarga única de unos 148 MiB.

PRIVACIDAD Y CONTROL
• Sin cuentas, sin analítica, sin anuncios, sin rastreadores.
• Tus credenciales de AWS se guardan cifradas en el dispositivo y no se envían al desarrollador.
• Conexión cifrada (HTTPS) con tu bucket.
• Incluye ayuda y un tutorial para configurar S3 paso a paso.

LIMITACIONES
• No es cifrado de extremo a extremo: lo que subes a S3 queda protegido por la configuración de tu bucket y tus permisos de AWS.
• Los álbumes privados solo admiten fotos (no vídeos) y no se respaldan en S3.
• Las credenciales temporales de AWS caducan y la app no las renueva sola.
• La app necesita Android 10 o superior y un dispositivo de 64 bits (ARM).
```

**Comprobaciones de veracidad.** Cada afirmación está respaldada en el código y en las pruebas: ver [library-tools.md](../library-tools.md), [private-albums.md](../private-albums.md), [semantic-search.md](../semantic-search.md), [security.md](../security.md) y [testing.md](../testing.md). Frases que **no** debe añadir nadie sin cambiar el código: «copias automáticas desde el primer momento», «cifrado de extremo a extremo», «privacidad absoluta», «sincronización instantánea», «recuperación garantizada».

## Recursos gráficos

| Recurso | Archivo | Estado |
|---|---|---|
| Icono 512×512 PNG 32 bits | [`assets/icon-512.png`](assets/icon-512.png) | Generado a partir del icono vectorial de la app (misma identidad). Revisar. |
| Gráfico de funciones 1024×500 | [`assets/feature-graphic-1024x500.png`](assets/feature-graphic-1024x500.png) | Borrador sencillo (marca + eslogan). El propietario puede sustituirlo. |
| Capturas de teléfono (7) | [`screenshots/`](screenshots/) | 1080×2160 (proporción 2:1, el máximo que admite Play), PNG de 24 bits sin alfa. Las fotos son paisajes dibujados por código (no hay fotos personales ni de terceros). |

Capturas incluidas: galería, selección con «Respaldar en S3», bienvenida del tutorial, «Cómo se guardan tus fotos», herramientas, ajustes y ayuda. **Faltan** (no se pueden mostrar sin una cuenta de AWS real): pestañas Respaldo y S3 con datos reales. Se recomienda añadirlas con un bucket de pruebas del propietario, sin fotos privadas. Las capturas de tableta (7 y 10 pulgadas) son opcionales; no se han hecho.

## Texto para «Novedades» de la versión 0.4.0 (máx. 500 caracteres)

```
• Tutorial de bienvenida y sección de ayuda.
• Centro de sincronización, auditoría del respaldo (solo lectura) y liberar espacio solo con lo verificado en S3.
• Duplicados, álbumes inteligentes y álbumes privados cifrados.
• Búsqueda por contenido opcional, en tu móvil.
• Mensajes de error más claros y mejoras de accesibilidad.
```

## Información de contacto (la exige Play)

Pendiente del propietario: correo electrónico de soporte, y opcionalmente sitio web y teléfono. Ver [owner-data.md](owner-data.md). No se ha inventado ninguno. Hay un canal público de incidencias en GitHub (`github.com/eliangilsierra/s3vault/issues`) que la app ya enlaza desde Ayuda.
