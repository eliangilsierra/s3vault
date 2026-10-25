# Cómo generar una versión de release

## Requisitos

- JDK 17 (el de Gradle) y el SDK de Android (`compileSdk 36`); ver [architecture.md](architecture.md) para las versiones fijadas.
- Una clave de firma **que no está en el repositorio**. El repositorio no contiene claves, contraseñas ni almacenes de claves (`*.jks`, `*.keystore` y `keystore.properties` están en `.gitignore`).

## Configurar la firma (sin poner secretos en el repositorio)

Hay dos formas; la primera tiene prioridad sobre la segunda, valor a valor.

1. **Archivo local `keystore.properties`** en la raíz (ignorado por git):

   ```properties
   storeFile=RUTA/ABSOLUTA/AL/almacen.jks
   storePassword=…
   keyAlias=…
   keyPassword=…
   ```

2. **Variables de entorno** (útil en CI): `S3VAULT_STORE_FILE`, `S3VAULT_STORE_PASSWORD`, `S3VAULT_KEY_ALIAS`, `S3VAULT_KEY_PASSWORD`.

Si faltan los cuatro valores, la compilación de release **sale sin firmar** (`app-release-unsigned.apk`) y no falla. Las dos rutas se comprobaron en esta versión: con `keystore.properties`, con solo variables de entorno y con ninguna.

## Generar los artefactos

```bash
# Paquete para Google Play (firmado si hay configuración de firma)
./gradlew bundleRelease
#   -> app/build/outputs/bundle/release/app-release.aab

# APK de release (para repartir por Firebase App Distribution o instalar a mano)
./gradlew assembleRelease
#   -> app/build/outputs/apk/release/app-release.apk
```

Por defecto la release incluye solo `arm64-v8a` (ver [semantic-search.md](semantic-search.md#peso-del-apk)). `-PreleaseAbis=arm64-v8a,armeabi-v7a` añade 32 bits y `-PreleaseAbis=arm64-v8a,x86_64` permite probarla en el emulador x86.

Si Gradle se queja de recursos desactualizados («mipmap/ic_launcher not found»), repetir con `--rerun-tasks`.

## Verificar la firma

```bash
# .aab: debe decir "jar verified" y mostrar el certificado esperado
jarsigner -verify -verbose:summary -certs app/build/outputs/bundle/release/app-release.aab

# .apk
$ANDROID_HOME/build-tools/<versión>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Resultado de la última comprobación (versión 0.4.0, versionCode 6)

| Artefacto | Resultado |
|---|---|
| `app-release.aab` | 18 991 437 bytes (18,1 MiB comprimido), solo `arm64-v8a`, firmado (RSA de 4096 bits, SHA-256) y **verificado con `jarsigner`**. Contiene el mapa de ofuscación (`proguard.map`) para desofuscar fallos en Play, y ningún archivo de depuración |
| `app-release.apk` | 35 238 516 bytes (33,6 MiB), `versionCode 6`, `versionName 0.4.0`, firma v2 **verificada con `apksigner`** |

El tamaño real de descarga lo calcula Play Console al subir el paquete; las cifras de arriba son las del archivo. No se ha subido ningún artefacto a Google Play.

## Antes de cada versión

1. Subir `versionCode` (y `versionName`) en `app/build.gradle.kts`: Play rechaza un `versionCode` igual o menor que uno ya subido.
2. Ejecutar las pruebas ([testing.md](testing.md)).
3. Repasar [play-store/release-checklist.md](play-store/release-checklist.md).
