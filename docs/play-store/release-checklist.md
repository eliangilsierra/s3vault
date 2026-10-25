# Lista de comprobación de publicación en Google Play

> Lo marcado «✅ hecho en el repositorio» se ha verificado (con el resultado indicado en [testing.md](../testing.md) y [release.md](../release.md)). Lo marcado «👤 propietario» **depende de ti o de Play Console** y no se ha hecho, ni se afirma que esté hecho. **No se ha subido, publicado ni enviado nada a Google Play.**

## A. Antes de entrar en Play Console

| | Paso | Estado |
|---|---|---|
| ✅ | `targetSdk 36` (requisito vigente para envíos nuevos y actualizaciones) y `minSdk 29`; `applicationId` sin cambios (`com.s3vault.app`) | hecho en el repositorio |
| ✅ | Versión **0.4.1 (versionCode 7)**. Cada subida a Play necesita un `versionCode` mayor que el anterior: **subirlo antes de cada nuevo envío** (`app/build.gradle.kts`) | hecho; mantener al día |
| ✅ | Paquete `app-release.aab` firmado, de solo `arm64-v8a`, sin archivos de depuración (ver [release.md](../release.md)) | hecho en el repositorio |
| ✅ | Permisos mínimos y justificados ([declarations.md](declarations.md)); sin SDK de analítica ni publicidad | hecho |
| ✅ | Pruebas: unitarias, instrumentadas en emulador, lint, R8 | ver [testing.md](../testing.md) |
| 👤 | Probar el `.aab` o el APK de release **en un teléfono real** (solo se ha probado en emulador) | pendiente |
| 👤 | Completar los datos de [owner-data.md](owner-data.md) | pendiente |
| 👤 | Alojar la política de privacidad en una URL pública y revisarla legalmente | pendiente |

## B. Decisión crítica sobre la firma (antes de la primera subida)

Las versiones repartidas hasta ahora por Firebase App Distribution están firmadas con la clave local de release. Con **Play App Signing**, Google firma lo que se instala desde Play. Hay dos caminos, y **no se puede deshacer** el elegido:

1. **Que Google genere la clave de firma** y usar la clave actual solo como **clave de subida** (lo recomendado por Google). **Consecuencia:** quienes ya tengan la app instalada desde Firebase **no podrán actualizar** a la de Play sin desinstalarla antes, porque la firma cambia. Al desinstalar se pierden los datos locales: índice, credenciales cifradas, álbumes privados y modelo. Solo es aceptable si no hay usuarios que conservar.
2. **Subir la clave actual como clave de firma de la app** durante el alta (Play Console lo permite con su herramienta de exportación cifrada). **Consecuencia:** la firma es idéntica y las instalaciones existentes se actualizan sin perder datos, pero la seguridad de esa clave pasa a depender de ti y de la copia que conserves.

👤 Comprobar el procedimiento vigente en la ayuda oficial de Play App Signing (puede cambiar), decidir, y guardar **una copia de seguridad cifrada de la clave y de su contraseña fuera del repositorio** en ambos casos.

## C. Play Console: crear y configurar la app

| | Paso |
|---|---|
| 👤 | Crear la app (nombre, idioma, app gratuita, declaraciones de política) |
| 👤 | Política de privacidad: pegar la URL ([privacy-policy-es.md](privacy-policy-es.md)) |
| 👤 | Acceso a la app: instrucciones y, si lo piden, bucket y credenciales de pruebas **aislados** |
| 👤 | Anuncios: no |
| 👤 | Clasificación de contenido (cuestionario IARC) |
| 👤 | Público objetivo (propuesta: 18+) |
| 👤 | Seguridad de los datos ([data-safety.md](data-safety.md)) |
| 👤 | **Declaración de permisos de fotos y vídeos** ([declarations.md](declarations.md), apartado 2) |
| 👤 | Ficha principal: textos de [listing-es.md](listing-es.md), icono, gráfico de funciones y capturas ([`assets/`](assets/), [`screenshots/`](screenshots/)); correo de contacto |
| 👤 | Países y regiones |

## D. Pruebas y producción

| | Paso |
|---|---|
| 👤 | Subir el `app-release.aab` a **pruebas internas** y comprobar la instalación desde Play en un teléfono real |
| 👤 | Si la cuenta es **personal creada después del 13 de noviembre de 2023**: ejecutar una **prueba cerrada con al menos 12 testers durante 14 días seguidos** y solicitar el acceso a producción (requisito vigente en la ayuda oficial; comprobar si te aplica) |
| 👤 | Revisar el informe previo al lanzamiento de Play y las advertencias de la consola |
| 👤 | Crear la versión de producción, añadir las «Novedades», enviar a revisión |

## E. Después de publicar

| | Paso |
|---|---|
| 👤 | Vigilar los informes de ANR y fallos de Play Console (la app no lleva informes propios) y las reseñas |
| 👤 | Cada versión nueva: subir `versionCode`, regenerar el `.aab`, repasar [data-safety.md](data-safety.md) si cambió algún flujo de datos |
| 👤 | Cuando Play cambie el `targetSdk` exigido, actualizarlo y repasar [declarations.md](declarations.md) |
