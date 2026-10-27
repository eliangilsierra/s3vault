# Sección «Seguridad de los datos» de Google Play: borrador de respuestas

> **Estado: borrador para que el propietario lo revise y lo rellene en Play Console. No se ha enviado nada.** Se basa en el comportamiento real de la versión 0.5.0 (código, manifiesto y las 254 dependencias de la versión de producción; auditadas sobre la 0.4.1, y la 0.5.0 no añade dependencias de producción ni cambia el manifiesto) y en las definiciones vigentes de Play, consultadas el 25 de octubre de 2026 en la ayuda oficial («Proporcionar información para la sección Seguridad de los datos»):
>
> - **Recopilar** = transmitir datos del usuario fuera del dispositivo, incluidos los enviados por bibliotecas o SDK, vayan al desarrollador o a un tercero.
> - **Compartir** = transferir datos recopilados a un tercero. Quedan exentas las transferencias a **proveedores de servicios** que actúan solo en nombre del desarrollador y las **acciones iniciadas por el usuario** que este espera razonablemente.
> - Hay que declarar lo que recojan los SDK incluidos, y **hace falta una política de privacidad** aunque no se recoja nada.
>
> Lo que sigue es una **interpretación razonable, no una garantía de aprobación**: Play decide, y el propietario es quien responde ante Play. Donde hay duda se dice.

## Resumen de la lógica

La app **no envía datos al desarrollador**. La única transmisión de datos de usuario fuera del dispositivo es la que **el propio usuario configura y activa**: subir sus fotos y vídeos a **su** bucket de Amazon S3. Eso cuenta como «recopilar» en la definición de Play (sale del dispositivo), pero el destino es una cuenta que controla el usuario, no el desarrollador ni un tercero elegido por él.

## Datos faciales de «Personas» (función opcional)

Definición vigente de Play (ayuda «Proporcionar información para la sección Seguridad de los datos», consultada el 26 de octubre de 2026): *recopilar* es **transmitir datos fuera del dispositivo**; los datos de usuario **«solo procesados localmente en el dispositivo y no enviados fuera de él» no hay que declararlos**. Las huellas faciales, posiciones, grupos y nombres de «Personas» **nunca salen del móvil** (comprobado: el análisis no pide red, el código no importa clases de red, una pasada de prueba envió y recibió 0 bytes; ver [face-grouping.md](../face-grouping.md#privacidad-y-datos)). Propuesta: **no declararlos como recopilados ni compartidos**, y describirlos con claridad en la política de privacidad (que Play exige igualmente y debe coincidir con lo que hace la app).

Lo que **no** está resuelto y debe valorarse antes de enviar:
- Las páginas de Play consultadas no contienen ninguna regla específica sobre datos biométricos; comprobar el asistente de Play Console y la política de «Datos de usuario» vigente el día del envío.
- Que Play no exija declararlo no cambia las obligaciones legales de privacidad (datos biométricos) del propietario: ver los bloqueos de [face-grouping.md](../face-grouping.md#bloqueos-antes-de-publicar-en-google-play).
- Decisión del propietario: por prudencia puede mencionar la función en el campo de descripción de privacidad de la ficha.

## Preguntas generales

| Pregunta de Play | Respuesta propuesta | Motivo |
|---|---|---|
| ¿La app recopila o comparte datos de usuario requeridos? | **Sí** (fotos y vídeos, y archivos/metadatos, cuando el usuario respalda) | Salen del dispositivo hacia el bucket del usuario. |
| ¿Todos los datos recopilados se cifran en tránsito? | **Sí** | HTTPS/TLS obligatorio en la versión de producción (`cleartextTrafficPermitted=false`). |
| ¿Se ofrece una forma de solicitar la eliminación de datos? | **No aplica / No** (explicado en la política) | El desarrollador no conserva ningún dato; el usuario borra desde la app (si da el permiso en AWS) o desde su consola de AWS, y desinstalar borra lo local. Confirmar la redacción exacta con el asistente de Play. |
| ¿Cumple la política de Familias? | No aplica | La app no está dirigida a niños. |

## Tipos de datos

| Categoría de Play | ¿Se recopila? | ¿Se comparte? | ¿Obligatorio u opcional? | Finalidad | Notas |
|---|---|---|---|---|---|
| **Fotos y vídeos** | **Sí**, solo si el usuario activa el respaldo | **No** (acción iniciada por el usuario hacia su propia cuenta; el desarrollador no lo recibe) | **Opcional** | Funcionalidad de la app | **Decisión del propietario:** si prefiere la lectura más conservadora, puede declararlo también como «compartido con un tercero (Amazon Web Services) por acción del usuario». |
| **Archivos y documentos** (nombres de archivo) | **Sí**, junto a la foto | **No** | Opcional | Funcionalidad de la app | El nombre original viaja como metadato del objeto en S3. |
| **Ubicación** | **Sin recopilación propia.** Los originales se suben **sin modificar** y pueden llevar la ubicación GPS dentro de los metadatos EXIF | No | n/a | n/a | La app **no lee ni usa** la ubicación, pero la copia del archivo la contiene. **Decisión del propietario** si lo declara o lo explica en la política (el borrador de política ya lo dice con claridad). |
| **Información personal (credenciales/tokens de AWS)** | **No como dato recopilado por el desarrollador.** La clave secreta nunca sale del móvil (la app firma las solicitudes allí); con credenciales temporales el *token de sesión* viaja a AWS como parte de la autenticación del propio usuario | No | n/a | Autenticación | **Pregunta abierta** para el asistente de Play: si exige declararlo como «Otra información» por viajar a AWS. |
| **Identificadores de dispositivo o publicidad** | No | No | n/a | n/a | No hay ID de publicidad, ni ID de instalación, ni analítica. |
| **Actividad en la app, información del dispositivo, rendimiento, diagnósticos** | No | No | n/a | n/a | No hay analítica ni informes de fallos remotos; los registros son locales y redactados. |
| **Contactos, calendario, mensajes, salud, finanzas, audio, navegación web** | No | No | n/a | n/a | La app no los toca. |

## Prácticas de seguridad (casillas de Play)

| Casilla | Respuesta | Respaldo |
|---|---|---|
| Datos cifrados en tránsito | Sí | TLS estándar de Android, sin `TrustManager` propio. |
| Puedes solicitar que se borren los datos | Ver arriba | Nada que borrar en poder del desarrollador. |
| Revisión de seguridad independiente | **No** | No se ha hecho (hay una revisión interna: [security.md](../security.md)). No marcar esta casilla. |
| Compromiso de seguir la política de Familias | No aplica | |

## Conexiones que NO son de datos del usuario pero conviene conocer

- **Descarga opcional del modelo de búsqueda** desde `huggingface.co` (HTTPS): Hugging Face ve la dirección IP y los datos técnicos de la petición. No se envía ninguna foto, vector ni identificador. La dirección IP **no es un tipo de dato de la lista de Play**; se menciona en la política de privacidad por transparencia.
- **Firebase App Distribution** se usa solo para repartir versiones de prueba a testers; **el SDK de Firebase no está en la app** (se comprobó en la lista de dependencias).

## Qué verificar antes de enviar

1. Releer las definiciones vigentes en Play Console (pueden cambiar).
2. Decidir las tres cuestiones marcadas como «Decisión del propietario» o «Pregunta abierta».
3. «Personas» no añade ninguna biblioteca nueva (reutiliza ONNX Runtime con dos modelos incluidos en la app). Si se añade **cualquier** biblioteca nueva (analítica, publicidad, fallos), rehacer este documento y la política.
4. Que la política de privacidad publicada diga lo mismo que las respuestas.
