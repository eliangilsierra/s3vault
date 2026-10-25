# Datos que debe proporcionar el propietario

Nada de esto se ha inventado ni está en el repositorio. Sin ellos no se puede completar la publicación.

| # | Dato | Dónde se usa | Estado |
|---|---|---|---|
| 1 | **Cuenta de desarrollador de Google Play** (tipo personal u organización) y quién es el titular | Play Console | Pendiente |
| 2 | **Nombre o razón social del responsable** y, si procede, domicilio | Política de privacidad; ficha de Play (las cuentas de organización muestran datos de contacto) | Pendiente |
| 3 | **Correo de soporte** (y opcionalmente sitio web y teléfono) | Ficha de Play (obligatorio el correo); política de privacidad | Pendiente |
| 4 | **URL pública de la política de privacidad** (hay que alojar el texto en un sitio del propietario) | Play Console (obligatoria) | Pendiente. El borrador está en [privacy-policy-es.md](privacy-policy-es.md) |
| 5 | **Revisión legal** de la política (país, base legal, autoridad de control) | Política | Pendiente |
| 6 | **Decisión sobre la clave de firma**: usar Play App Signing con la clave actual como clave de subida (recomendado, ver [release-checklist.md](release-checklist.md)) o aportar otra | Subida del `.aab` | Pendiente |
| 7 | **Copia de seguridad de la clave de firma** y de su contraseña, fuera del repositorio | Seguridad del proyecto | Pendiente de confirmar. Hoy el almacén de claves está en una carpeta local fuera del repositorio y sus datos en `keystore.properties` (ignorado por git) |
| 8 | **Bucket y credenciales de pruebas aislados** para que el equipo de revisión de Play pueda probar el respaldo (si lo exigen) | «Acceso a la app» | Pendiente. **Nunca** poner credenciales en el repositorio |
| 9 | **Categoría** y **etiquetas** definitivas | Ficha | Pendiente (propuesta en [listing-es.md](listing-es.md)) |
| 10 | **Países/regiones** de distribución | Play Console | Pendiente |
| 11 | **Capturas finales** (se incluyen 7 de borrador; faltan las pestañas Respaldo y S3 con datos reales) y, opcionalmente, de tableta | Ficha | Parcial |
| 12 | Aprobación del **gráfico de funciones** y del **icono** (generados a partir del icono actual) | Ficha | Pendiente |
| 13 | **Respuestas finales** de «Seguridad de los datos», de la clasificación de contenido y del público objetivo | Play Console | Borradores en [data-safety.md](data-safety.md) y [declarations.md](declarations.md) |
| 14 | Si la cuenta es **personal creada después del 13 de noviembre de 2023**: **12 testers** en una prueba cerrada durante **14 días seguidos** antes de pedir acceso a producción (requisito vigente en la ayuda oficial) | Calendario de la publicación | Pendiente de comprobar el tipo de cuenta |
| 15 | Decisión sobre el **tamaño** (33,6 MiB por ONNX Runtime) y la limitación a **arm64** | Ficha, compatibilidad | Decidida por defecto (arm64); confirmar |
