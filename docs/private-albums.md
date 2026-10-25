# Álbumes privados

Fotos que solo se abren después de verificar tu identidad con huella, PIN, patrón o contraseña del dispositivo. **Copian, cifran y esconden; no mueven ni borran nada sin que lo confirmes aparte.**

## Qué es (y qué no es)

| Es | No es |
|---|---|
| Una copia **cifrada** (AES-256-GCM) de cada foto en el almacenamiento privado de la app, con su miniatura y todos sus datos (nombre, tipo, tamaño, fechas, nombre del álbum) también cifrados. | Un simple «ocultar»: ocultar no es cifrar, y aquí no se esconde un archivo en claro. |
| Una puerta con la identidad del usuario: la clave del vault solo se puede usar tras autenticarse. | Protección contra un móvil con root, ni contra malware que corra dentro de la app mientras los álbumes están abiertos. |
| Local: nada sale del teléfono, no hay servidor ni cuenta. | Un respaldo: las fotos protegidas **no se suben a S3** y, si pierdes la clave, no hay copia. |

## Qué hace la app, paso a paso

1. En la galería seleccionas fotos → «Mover a álbum privado…». Si no hay vault, se crea tras verificar tu identidad (con aviso de qué pasa si la clave se pierde).
2. Eliges o creas un álbum. Un diálogo explica que **se copian cifradas, se comprueba cada copia y los originales no se borran ni se mueven**, cuántas ya tienen copia en S3 (esa copia no se cifra ni se borra) y que los vídeos no se pueden proteger todavía.
3. Cada foto se cifra a un archivo temporal, se **vuelve a leer y descifrar para comprobar que coincide con lo leído del original** (SHA-256 y tamaño), se fuerza a disco, se renombra y solo entonces se inserta su fila en la base de datos. Un fallo a mitad no deja ninguna fila apuntando a nada.
4. Al terminar, «Eliminar originales…» pasa **solo las fotos verificadas** al flujo de borrado que ya existía (que pregunta si quieres borrar del dispositivo o de S3, con sus avisos habituales). «Conservar originales» las deja donde están. La app nunca borra un original porque se haya copiado.

Sacar una foto del álbum («Sacar del álbum») escribe primero una copia descifrada en `Pictures/S3Vault` (MediaStore), comprueba su SHA-256 contra el guardado al entrar y **solo entonces** borra la copia cifrada. Si no coincide, la foto sigue protegida. Eliminar del álbum (foto o álbum entero) es permanente y se confirma; no toca copias en la galería ni en S3.

## Cifrado

Formato `S3VP` v1 ([`VaultCrypto`](../app/src/main/kotlin/com/s3vault/app/domain/privacy/VaultCrypto.kt)):

```
cabecera: "S3VP" | versión | log2(tamaño de fragmento) | id de archivo (16 bytes aleatorios)
cuerpo:   fragmentos de 64 KiB cifrados + etiqueta GCM de 16 bytes
```

- **Cifrado autenticado por fragmentos**, para descifrar en streaming con memoria acotada.
- **Una clave por archivo**, derivada de la clave del vault y del id del archivo con HMAC-SHA-256; la clave del vault nunca cifra datos directamente.
- El *nonce* de un fragmento es su índice (una clave nunca repite nonce); los **datos autenticados** son la cabecera, el índice y «es el último»: no se pueden reordenar, duplicar, quitar ni cambiar de archivo fragmentos, y un archivo **truncado** falla en vez de mostrarse más corto.
- Probado: ida y vuelta en los tamaños donde el fragmentado suele romperse (0, 1, 15, 16, 64 KiB − 1, 64 KiB, +1, varios fragmentos), alteración de cualquier byte (cabecera, datos, etiqueta), cortes en cada límite, bytes añadidos, fragmentos reordenados/duplicados/entre archivos, clave equivocada, formato desconocido.
- Nombres de archivo en disco: 16 bytes aleatorios en hexadecimal, sin relación con la foto. En la base de datos, el nombre del álbum y los metadatos de cada foto son bloques cifrados: **con el vault bloqueado, la base solo dice que hay álbumes** (hay una prueba que busca nombres, tipos y carpetas en claro en todas las columnas y en todos los archivos).

## La clave

[`KeystoreKeyring`](../app/src/main/kotlin/com/s3vault/app/data/privacy/KeystoreKeyring.kt):

- La clave del vault (32 bytes aleatorios) se guarda **cifrada** por una clave AES-256-GCM del **Android Keystore** (respaldada por hardware si el dispositivo lo tiene; se intenta StrongBox y se cae al Keystore normal) que **exige autenticación del usuario**.
- Android 11+: **cada uso** de la clave exige su propia autenticación (huella fuerte o credencial del dispositivo), atada a la operación con un `CryptoObject`. Android 10: el framework no puede atar una credencial a una operación, así que la clave funciona unos segundos tras autenticarse.
- El *prompt* es el del sistema (`BiometricPrompt` del framework, sin dependencias nuevas), con **PIN/patrón/contraseña como alternativa segura**. Si el dispositivo no tiene ningún bloqueo de pantalla, la función **no se ofrece** (se explica y se lleva a los ajustes de Android).
- La clave se crea sin «invalidar al registrar una huella nueva»: añadir una huella no destruye el vault.
- En memoria, la clave solo existe mientras la sesión está desbloqueada ([`PrivateSession`](../app/src/main/kotlin/com/s3vault/app/domain/privacy/PrivateSession.kt)); al bloquear se sobrescribe con ceros. Nada se persiste: un proceso nuevo siempre empieza bloqueado.
- Permiso nuevo: **`USE_BIOMETRIC`** (permiso normal, sin diálogo ni acceso a datos; el *prompt* del sistema lo exige). Sin permisos IAM ni de red nuevos.

## Cuándo se bloquea

Al pulsar «Bloquear ahora», **al apagarse la pantalla**, y cuando la app lleva fuera de primer plano más que el ajuste elegido (al salir / 1 minuto, por defecto / 5 minutos). Al bloquear:

- se vacía la caché de imágenes descifradas (solo en memoria, **nunca** en disco ni en la caché de Coil),
- una operación en curso se detiene en la siguiente foto (lo hecho hasta entonces es válido),
- cualquier pantalla privada abierta se **sustituye de inmediato** por la entrada bloqueada.

La navegación hace lo mismo con pilas restauradas tras morir el proceso (arrancan bloqueadas). No hay *deep links* ni intents que abran pantallas privadas. Las pantallas privadas usan `FLAG_SECURE`: sin capturas, grabaciones ni vista previa en recientes.

## Pérdida de la clave: qué implica

**No hay recuperación, por diseño** (no hay servidor ni copia de la clave; sería una puerta trasera). Las fotos cifradas se pierden para siempre si:

- se **quita el bloqueo de pantalla** (Android borra las claves que lo exigen),
- se restablece el móvil, se desinstala la app o se borran sus datos,
- se restaura una copia en otro dispositivo (de todos modos, las copias de seguridad de Android están desactivadas para el índice y los archivos de la app).

Qué hace la app: si la clave ya no existe o no abre los archivos, muestra «La clave de los álbumes privados se perdió», **no borra nada por sí sola** y ofrece, con confirmación, eliminar los archivos inservibles para liberar espacio y empezar de nuevo. Antes de cambiar de móvil o de quitar el bloqueo: «Sacar del álbum».

## Dónde no aparecen

Las fotos protegidas **no están en el índice de la galería** (tablas distintas: `private_albums`, `private_items`), así que ninguna consulta de la galería, los recuentos, las carpetas, los álbumes, la búsqueda, el centro de sincronización, la auditoría, «Liberar espacio», los duplicados ni la búsqueda por contenido pueden verlas. No se respaldan en S3. Mientras el **original** siga en la galería (por decisión del usuario), seguirá apareciendo en todo eso, y su copia en S3 seguirá en la pestaña S3: la app lo dice en cada paso y no lo toca sin permiso.

## Limitaciones (sinceras)

- Solo **fotos**: los vídeos se omiten con un aviso (reproducirlos exigiría descifrar con acceso aleatorio o un archivo temporal en claro).
- **No se respaldan** en S3: ni cifradas ni en claro.
- La copia de S3 de una foto ya respaldada no se cifra ni se borra automáticamente; la decide el usuario con el flujo de borrado existente.
- El trabajo de mover es en primer plano (la clave solo existe tras autenticarse): no es trabajo de WorkManager. Es cancelable, informa del progreso y lo copiado hasta cancelar es válido.
- No protege frente a root, malware en la propia app con el vault abierto, ni ataques físicos al hardware de seguridad del dispositivo.
- La validación real con huella dactilar no se probó (el emulador solo tiene PIN); el camino del PIN, sí (ver [testing.md](testing.md)).
