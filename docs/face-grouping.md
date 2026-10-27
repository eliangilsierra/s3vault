# Personas: agrupación de rostros y respaldo por persona

> **Estado (26 de octubre de 2026, versión 0.5.0, sin publicar):** implementada de punta a punta y probada en JVM y en emulador. **No está lista para publicarse en Google Play con la función** mientras no se resuelvan los puntos de «Bloqueos antes de publicar» (al final); mientras tanto se puede publicar **sin ella** ([Compilar sin la función](#compilar-sin-la-función)). Es software libre «tal cual»: ver el [aviso del README](../README.md#aviso-y-responsabilidad) y [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md). Nada de lo que sigue es una garantía de precisión: las cifras son mediciones de esta implementación sobre los datos indicados.

## Qué hace

1. Opcional y desactivada al principio. Al abrir **Herramientas → Personas** (o la fila «Personas» de la pestaña Álbumes) la app explica qué hace, dónde viven los datos y cómo se borran; el análisis solo empieza si el usuario pulsa «Activar análisis de rostros».
2. Detecta rostros en las fotos del dispositivo, calcula para cada uno una huella de 128 números y agrupa los que se parecen. Todo en el móvil.
3. Los grupos se presentan como **estimaciones**: «Persona 1», «Persona 2»… (nunca un nombre inventado) hasta que el usuario los nombra. El usuario puede confirmarlos, renombrarlos, quitar fotos, separar, mover, combinar, ocultar («no sugerir más») o eliminar un grupo.
4. Las coincidencias dudosas no se aplican: van a **Revisar** (posibles coincidencias, grupos que podrían ser uno solo, rostros sin grupo, fotos que una regla no quiso decidir). Un «no» se recuerda.
5. En cada persona, «Incluir en respaldo automático» crea una regla: las fotos **nuevas** en las que aparezca se añaden a la cola de respaldo **ya existente**. Opcionalmente (y solo tras ver el número y el tamaño y poder excluir fotos) también las que ya estaban.

Las operaciones sobre grupos cambian **solo el índice facial**: nunca un archivo, un registro de respaldo ni un objeto de S3.

## Cómo está construido

```
MediaStore ──(escaneo existente)──► media_items ──► cola implícita: fotos sin análisis válido (face_photos)
                                                         │  FaceIndexWorker (WorkManager, sin red, batería no baja)
                                                         ▼
 AndroidFaceImageSource ─► FacePipeline: YuNet (detección) → alineación 5 puntos → calidad → SFace (128 números)
                                                         │  sellado AES-256-GCM (FaceVault)
                                                         ▼
 faces / people / face_rejections … (Room v12) ◄── FaceGroupingStore (asignar · sugerir · formar grupos · fusionar)
                                                         ▲                 ▲
                              FacePeopleController (correcciones del usuario)     FaceBackupService (reglas)
                                                                                  │ enqueueForRule()
                                                                                  ▼
                                         BackupController / cola `media_items` / BackupEngine / S3 (sin cambios)
```

| Pieza | Archivo |
|---|---|
| Modelos, umbrales, geometría, calidad, decodificador de YuNet | `domain/faces/FaceModel.kt`, `FaceGeometry.kt` |
| Agrupación (puro, sin Android) | `domain/faces/FaceGrouping.kt` |
| Política de respaldo (puro) | `domain/faces/FaceBackupPolicy.kt` |
| Sellado de huellas | `domain/faces/FaceVault.kt`, `data/faces/SecretDataKeyStore.kt` |
| ONNX Runtime (detector + reconocedor) | `data/faces/OrtFaceEngine.kt`, `FaceModels.kt` |
| Decodificación (EXIF) y recortes en memoria | `data/faces/FaceImageSource.kt`, `FaceCrops.kt` |
| Entidades, DAO, migración 11→12 | `data/local/FaceEntities.kt`, `FaceDao.kt`, `AppDatabase.kt` |
| Análisis incremental y trabajador | `sync/FaceIndexer.kt`, `Scheduling.kt` (`FaceIndexWorker`) |
| Agrupar, correcciones, reglas | `sync/FaceGroupingStore.kt`, `FacePeopleController.kt`, `FaceBackupService.kt` |
| Interfaz | `presentation/people/*` (textos en `res/values/strings_people.xml`) |

**Una sola cola de respaldo.** `FaceBackupService` no sube nada ni crea un sistema paralelo: llama a `MediaDao.enqueueForRule`, que mueve fotos de `LOCAL_ONLY` a `QUEUED` en la misma tabla que usa el respaldo manual. Desde ahí intervienen, sin cambios, `BackupEngine` (SHA-256, comprobación, reintentos), la clave `{prefijo}photos/{assetId}/{versionId}/{nombre}`, las restricciones de red y batería, la pausa y los errores de AWS.

**Por qué `enqueueForRule` y no `enqueueAll`.** `enqueueAll` pone `backupExcluded = 0` y también reencola las fotos `FAILED`. Una regla por rostro volvería a subir una foto cuya copia de S3 el usuario borró a propósito. `enqueueForRule` solo mueve fotos `LOCAL_ONLY`, presentes y con `backupExcluded = 0` (cubierto por pruebas y por una mutación deliberada).

## El modelo: elección y alternativas

La detección sola no identifica a nadie entre fotos; hace falta un modelo de huellas. Se evaluó (licencias consultadas el 26 de octubre de 2026):

| Opción | Licencia | Tamaño / dependencias | Veredicto |
|---|---|---|---|
| **YuNet 2023mar + SFace 2021dec** (OpenCV Zoo) sobre **ONNX Runtime** (ya incluido en la app) | YuNet **MIT** (Shiqi Yu); SFace **Apache-2.0** (OpenCV Zoo) | 0,23 MB + 18,5 MiB; sin Play Services, sin descargas | **Elegido** (con la salvedad de los datos de entrenamiento, ver «Bloqueos») |
| ML Kit Face Detection (+ un modelo de huellas) | Términos de ML Kit | +6,9 MB (empaquetado) o ~0,8 MB (vía Play Services) | Solo detecta; no da huellas. Habría que añadir igualmente un modelo. Descartado por no aportar frente a YuNet (cuyos 5 puntos son justo los que SFace espera) |
| InsightFace (ArcFace, buffalo_l/s, w600k_*) | Código MIT, **pesos solo para investigación no comercial** (README de InsightFace; para uso comercial piden licencia aparte) | 5–170 MB | **Descartado**: no redistribuible en una app comercial |
| AuraFace v1 (fal.ai) | Apache-2.0; datos «comerciales y públicos» sin detallar | ResNet100, 512 números, ~130 MB en fp16 | Alternativa viable si se necesitara otra procedencia de datos; demasiado pesada para el móvil de gama media sin medirla |
| MobileFaceNet / FaceNet de repositorios sueltos | Código MIT/Apache, pesos entrenados con MS-Celeb-1M, VGGFace2 o CASIA-WebFace (conjuntos de investigación; MS-Celeb-1M fue retirado por Microsoft) | 1–100 MB | Sin licencia clara de los pesos: descartado |
| «Simular» reconocimiento con landmarks o seguimiento temporal | — | — | **No se hizo**, como exigía el encargo |

Archivos (SHA-256 verificados en la descarga y en cada carga):

| Archivo en `assets/faces/` | Origen | Bytes | SHA-256 |
|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | idéntico al de OpenCV Zoo | 232 589 | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` |
| `face_recognition_sface_2021dec_fp16w.onnx` | **derivado** de `face_recognition_sface_2021dec.onnx` (38 696 353 B, `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79`) | 19 373 514 | `faec3ea2b4831cc0ab3cf067e9e88108ca849efb948cad591f7f1f9cc9d3bb2e` |

**Qué se modificó del modelo SFace (aviso de modificación, Apache-2.0 §4):** los pesos float32 se guardan como float16 con un nodo `Cast` a float32 al cargar, y se quitan los pesos de la lista de entradas del grafo. El cálculo sigue siendo float32. La conversión se hizo con un script local que no forma parte del repositorio. Se midió contra el original: coseno del mismo rostro ≥ 0,999994, y resultados de verificación idénticos (tabla siguiente). Los textos de licencia van junto a los modelos (`assets/faces/LICENSE-*.txt`).

**Formatos descartados tras medir:**

| Variante | AUC | TAR a FAR 10⁻⁴ | Velocidad (CPU de escritorio, 1 hilo) | Tamaño | Veredicto |
|---|---|---|---|---|---|
| fp32 original | 0,99918 | 97,4 % | 17,3 ms | 38,7 MB | referencia |
| **fp16 en disco (usado)** | 0,99918 | 97,4 % | 12,4 ms | **19,4 MB** | **idéntico a fp32** |
| int8 dinámico (el de OpenCV Zoo) | 0,99907 | 96,7 % | **207–266 ms** (usa `ConvInteger`) | 9,9 MB | 12× más lento: descartado |
| int8 estático QDQ propio (calibrado con 400 rostros de LFW ajenos a la muestra) | 0,99896 | **94,2 %** | 13 ms | 10,0 MB | pierde precisión apreciable: descartado |

## Mediciones de calidad (y sus límites)

**Verificación (¿son la misma persona?)** sobre una muestra de **LFW** (7 420 rostros, 2 680 identidades, 14 508 pares de la misma persona, 27 509 982 pares de personas distintas; hasta 10 fotos por persona más 1 000 personas con una sola foto). LFW se usa solo localmente, no se redistribuye. Detección con YuNet, alineación propia, SFace:

| Umbral de similitud coseno | TAR (misma persona aceptada) | FAR (distinta aceptada) |
|---|---|---|
| 0,363 (el que recomienda OpenCV) | 98,8 % | 9,7·10⁻⁴ |
| 0,450 | 95,9 % | 2,6·10⁻⁵ |
| 0,500 | 91,8 % | 3,0·10⁻⁶ |
| 0,600 | 72,5 % | 6,5·10⁻⁷ |

Una implementación de referencia en Python (script local, no incluido en el repositorio) se validó contra la de referencia de OpenCV (`FaceDetectorYN`, `FaceRecognizerSF`): cajas ±1,4 px, landmarks ±1,1 px, coseno de las huellas 0,986 de media.

**Efecto de la calidad del rostro** (similitud entre pares de la misma persona, por el peor rostro del par): ojos a 20–30 px → media 0,583 (330 pares) frente a 0,659 con ojos a 40–55 px; cabeza girada (yaw 0,55–0,8) → 0,579 frente a 0,658 de frente; foco por debajo de 40 → 0,614. Por eso los rostros pequeños (< 20 px entre ojos), borrosos, oscuros o de perfil **no se comparan** (se cuentan y se descartan) y la calidad pesa en la política de respaldo.

**Agrupamiento incremental** (simulación con el mismo algoritmo, rostros en lotes de 150 en orden aleatorio, 3 semillas, configuración final): de 7 420 rostros se agrupan 4 444–4 488; **5–9 quedan en un grupo cuya identidad mayoritaria es otra (0,1–0,2 %)**; recall de identidades con ≥ 3 fotos ≈ 91 %; ninguna identidad queda partida en varios grupos; las sugerencias (44 en la semilla 0) aciertan el 86 %. Con un umbral de sugerencia más bajo (0,42) el acierto de las sugerencias era del **7 %**: por eso la banda es estrecha (0,52–0,58).

**Qué son los pocos errores restantes** (revisados uno a uno): dos son etiquetas duplicadas de LFW (Sepp/Joseph Blatter), y el resto son **gemelos y parientes** (Mary-Kate y Ashley Olsen 0,84; Claire y Morgan Hentzen 0,87; Rubens Barrichello y Michael Schumacher 0,85) y un falso positivo real (0,71). **Ningún umbral separa a unos gemelos idénticos**: las puntuaciones de los errores (mediana 0,85) son tan altas como las de los aciertos (0,86). Subir los umbrales estrictos de respaldo no reduce estos errores de forma medible; lo que los contiene es que el grupo lo confirme el usuario y que las dudas pasen por revisión.

**Fotos reales de dominio público** (3 personas, 3 fotos cada una, con décadas de diferencia; `app/src/test/resources/faces/`): misma persona 0,28–0,69, personas distintas ≤ 0,33. Con 0,28–0,57 entre fotos de épocas muy distintas, **esas fotos no se agrupan solas** (hace falta el umbral 0,55 de enlace): quedan como «rostros sin grupo» y el usuario puede crear la persona a mano. En la foto de la tripulación STS-93 (5 caras) y en una rueda de prensa (4), exactamente una cara coincide con la persona de los retratos (0,85/0,73/0,57 y 0,52).

**Lo que estas cifras no demuestran:** LFW son retratos frontales de personas públicas, más fáciles que una galería real (niños que cambian, perfiles, mascarillas, iluminación pobre, familiares parecidos). Pruebas con fotos de una galería personal real **no se hicieron** (restricción de privacidad del encargo). No se afirma ninguna precisión para el uso real; por eso toda decisión de respaldo exige confirmación del usuario o una coincidencia muy segura y de buena calidad, y las dudas se preguntan.

## Algoritmo de agrupación

- **Colocar un rostro nuevo** (`FaceGrouper.place`): similitud con el centro de cada persona (media de sus huellas), excluyendo las personas que el usuario rechazó para ese rostro y las que ya tienen **otro rostro de la misma foto** (dos caras de una foto son dos personas). `assign` ≥ 0,58 **y** ventaja sobre la segunda persona ≥ 0,10 **y** calidad ≥ 0,30 → se coloca (AUTO); entre 0,52 y 0,58, o con poca ventaja o calidad → **sugerencia** (no pertenece a nadie hasta que el usuario responda); por debajo → queda suelto.
- **Grupos nuevos** (`newGroups`): cada rostro nuevo suelto es semilla; reúne a sus vecinos con similitud ≥ 0,55 (como mucho uno por foto), descarta los que no se mantienen ≥ 0,50 respecto a la media del grupo y exige **≥ 3 rostros de ≥ 3 fotos distintas**. Solo mira vecinos de la semilla (nunca vecinos de vecinos): una cadena de parecidos no puede estirar un grupo entre personas distintas. Coste: semillas × sueltos, no todos contra todos.
- **Fusión automática** solo entre grupos **sin revisar** con centros ≥ 0,70 y sin fotos en común; entre 0,55 y 0,70, o si alguno está confirmado, se **propone** al usuario («¿La misma persona?»). Un «son distintas» se recuerda.
- **Correcciones que se conservan:** un rostro quitado de un grupo queda rechazado para él; lo confirmado, movido o combinado por el usuario (CONFIRMED) nunca lo cambia el algoritmo; si una foto se vuelve a analizar (cambio de versión del pipeline) los rostros nuevos **toman el lugar de los anteriores** por solapamiento de cajas (IoU ≥ 0,4) y conservan grupo, confirmación y rechazos. Si cambia el **modelo**, lo automático se rehace en el espacio nuevo y lo confirmado se conserva.
- **Escala:** colocar 600 rostros nuevos entre 12 000 agrupados, 3 000 sueltos y 300 grupos: **434 ms**, sin colocar ninguno en un grupo al que no se parezca (`FaceScaleTest`, JVM).

## Respaldo automático por persona

Decide `FaceBackupPolicy` (puro) con la puntuación y el margen **recalculados al evaluar** (sin contar al propio rostro en el centro del grupo): lo guardado al colocar el rostro es historia.

| Situación | Resultado |
|---|---|
| Rostro **confirmado** por el usuario | Se añade a la cola |
| Automático, grupo confirmado, similitud ≥ 0,60, ventaja ≥ 0,15, calidad ≥ 0,45 (política **prudente**, por defecto) | Se añade |
| Lo mismo pero con menos seguridad | **Revisar** (con el motivo: podría ser otra persona / parecido insuficiente / rostro pequeño, borroso o de perfil) |
| Solo una **sugerencia** | Revisar; nunca se sube sola |
| Grupo **sin revisar** | Revisar (la hoja exige confirmar el grupo antes de continuar) |
| Política «también probables» (elección explícita del usuario) | Añade coincidencias automáticas con calidad ≥ 0,30 y ventaja ≥ 0,15; las ambiguas siguen en Revisar |
| Foto ya respaldada, en cola, subiendo, verificando o fallida | No se toca |
| Foto cuya copia en S3 el usuario borró a propósito | No se toca |
| Foto excluida por el usuario o descartada en Revisar | No se toca |
| Persona oculta | Sin regla |
| Foto con varias personas | Se sube **la foto original completa** una sola vez si **alguna** persona con regla cumple; nunca un recorte |

**Fotos nuevas** = las que entran en MediaStore (`date_added`) a partir del momento de activar la regla. **Fotos existentes:** solo si el usuario lo elige, después de ver cuántas son y cuánto pesan (con aviso si son ≥ 200), pudiendo excluir fotos una a una; se aplican en lotes de 200 y el proceso sobrevive a un reinicio (`existingPending`). Las coincidencias antiguas que aparezcan después (por una fusión o un movimiento) **no** se suben solas: el usuario las respalda a mano o las descarta. Reanudar una regla en pausa no «recupera» lo llegado durante la pausa.

**Control:** pausar, reanudar, quitar la regla, y **Deshacer** (saca de la cola lo que la regla puso y aún no empezó a subirse; deja a esas fotos como estaban, sin marcarlas como excluidas). Quitar la regla, borrar la persona o borrar el índice **nunca** borra fotos, copias ni objetos de S3, y no toca lo ya encolado salvo con «Deshacer».

**Con el respaldo automático general activado** (Ajustes generales) ya se suben todas las fotos; la app lo dice en la pantalla de la persona.

**Diferido por el sistema:** el análisis y la subida los decide WorkManager (batería no baja y, por defecto, con el móvil cargando; la subida con tus ajustes de red). Nada es instantáneo y la interfaz no lo promete.

## Privacidad y datos

| Dato | Dónde | Protección |
|---|---|---|
| Fotos | MediaStore (sin copiar) | La app las lee en memoria a ≤ 1 280 px y descarta el bitmap |
| Posición del rostro (4 fracciones) y puntuaciones | Room (`faces`) | Almacenamiento privado de la app; excluido de copias del sistema |
| **Huella de 128 números** (cuantizada a 132 bytes) | Room (`faces.sealed`) | **AES-256-GCM** por huella; la clave de datos vive en el archivo cifrado por una clave del **Android Keystore** (`SecretStore`, directorio *no-backup*); la finalidad se autentica junto con el dato |
| Centro de cada grupo | Room (`people.centroidSealed`) | Igual |
| Nombres que pone el usuario, correcciones, reglas | Room | Privado de la app; **los nombres no se cifran** |
| Recortes de caras | **No se guardan**: se recortan de la foto al dibujarlos y viven en una caché de memoria de ≤ 6 MB | — |
| Rostros demasiado pequeños, borrosos u oscuros | Solo un contador por foto | — |

- **Nada sale del móvil.** El trabajo de análisis no pide red (sin restricción de red en WorkManager), el código de rostros no importa clases de red ni de S3 (prueba estática), y en el emulador una pasada de 11 fotos envió y recibió **0 bytes** (`TrafficStats` por UID). Los modelos vienen dentro de la app: no hay descarga.
- **Sin permisos nuevos.** La prueba `FacePrivacyGuardTest` falla si cambia la lista del manifiesto.
- **Sin copias del sistema.** `allowBackup="false"` y reglas que excluyen base de datos, archivos y preferencias; la clave está en el directorio *no-backup*.
- **Registros.** Las llamadas a `SafeLog` del código de rostros solo llevan texto constante (prueba estática); no hay huellas, cajas ni nombres en registros, notificaciones ni informes (no hay informes remotos).
- **Qué no protege** el cifrado: código que corra dentro de esta app o un móvil con acceso de superusuario pueden pedir la clave igual que la app; una copia de la base de datos sin la clave no es legible. La clave **no** exige desbloqueo del usuario a propósito (el análisis corre en segundo plano). Los nombres de las personas sí están en claro en la base de datos.
- **Si la clave se pierde** (por ejemplo, un fallo del Keystore) las huellas no se pueden abrir: la pantalla lo dice y ofrece borrar y reconstruir; no se genera una clave nueva en silencio.
- **Borrado.** *Borrar índice facial* (Ajustes → Personas y rostros) elimina huellas, posiciones, grupos, nombres, correcciones y reglas, **destruye la clave** y desactiva el análisis. Desactivar el análisis solo lo detiene. Las fotos, las copias en S3 y lo encolado no se tocan. Una foto que sale del dispositivo se lleva sus rostros (`dropOrphans`).
- **Qué va a S3:** las fotos que el usuario respalda, enteras y como siempre (con el prefijo configurado, sin cambiar la estructura de claves). Nada más: ni huellas, ni recortes, ni grupos, ni nombres. No hay infraestructura nueva en AWS, ni cambios de IAM.

## Room (versión 12)

Tablas nuevas, sin tocar las existentes (migración 11→12 aditiva, probada con una base v11 con datos): `face_photos` (estado de análisis por foto y versión del pipeline), `faces`, `people`, `face_rejections`, `person_merge_rejections`, `face_backup_rules`, `face_rule_exclusions`, `face_backup_decisions` (revisión, deshacer, idempotencia). Índices por foto, persona, asignación y resultado de decisión; claves foráneas con `CASCADE`/`SET NULL` (borrar una persona borra su regla, sus exclusiones y rechazos y libera sus rostros). Una foto se considera analizada solo si coinciden tamaño, fecha de modificación, versión del pipeline y modelo; si no, se vuelve a analizar conservando las correcciones. Tamaño medio del índice: unos 0,25 KB por rostro (100 000 rostros ≈ 25 MB).

## Rendimiento, batería y memoria

- Todo el trabajo pesado va fuera del hilo principal (`Dispatchers.Default`/`IO`; hay una prueba que lo comprueba) con 1 foto a la vez por defecto (hasta 3 si se desactiva «Usar pocos recursos»), por tandas de 300 fotos y 8 minutos; el resto continúa en una nueva ejecución. Se detiene y reanuda sin repetir trabajo.
- La cola es la propia base de datos (fotos sin análisis válido): sobrevive a un cierre, un reinicio o un reinicio del móvil, sin tabla de cola aparte. No se reescanea la galería al abrir la app: solo se despierta el análisis cuando el escaneo ya existente encuentra fotos nuevas.
- Memoria: una imagen de trabajo (≤ 1 280 px, unos 5 MB) por foto en curso y los modelos solo mientras corre el análisis (el reconocedor son 19,4 MB en disco y, una vez cargado en float32, del orden de decenas de MB; la memoria nativa **no se midió**); no se guardan bitmaps. Medido en el emulador: +9,5 MB de montón de Java en una pasada.
- Velocidad: **88 ms por foto** (decodificar + detectar + describir) en el emulador x86_64 de 4 núcleos, con fotos de 960 px y 1,5 rostros de media; en Python/escritorio, 18 ms detectar y 12 ms por rostro. **No se midió en un móvil ARM real** (el emulador no es representativo): con estas cifras una biblioteca de 10 000 fotos tardaría del orden de la media hora en el emulador, y el tiempo en un móvil de gama media está sin medir.
- Rostros pequeños: YuNet tiene entrada fija de 640 px; una foto de 12 MP se reduce a 640 para detectar, de modo que un rostro menor de ~4 % del ancho de la foto puede no detectarse en fotos de grupo grandes.

## Tamaño

| | Antes | Ahora |
|---|---|---|
| APK de release (arm64) | 33,5 MiB | **51,3 MiB** (53 785 255 B) |
| AAB de release | — | 35,8 MiB (37 527 708 B) |

El aumento (~17,8 MiB) es casi todo el modelo SFace (18,5 MiB; los pesos en float16 apenas se comprimen). Alternativas que se evaluaron: SFace int8 (10 MB, pero 12× más lento o menos preciso: descartado), descarga bajo demanda (como el modelo de búsqueda por contenido; reduciría el instalador pero obliga a una descarga de terceros y a verificar hashes: no se hizo para que el análisis no necesite red nunca).

## Compilar sin la función

Para quien distribuya la app (por ejemplo, en una tienda) y no quiera cargar con los puntos de [«Bloqueos»](#bloqueos-antes-de-publicar-en-google-play), el proyecto admite una compilación **sin «Personas»**:

```bash
./gradlew :app:assembleRelease -PwithFaces=false     # APK
./gradlew :app:bundleRelease   -PwithFaces=false     # AAB para Play
```

Qué cambia con `-PwithFaces=false` (por defecto es `true`):

- Los dos modelos y sus licencias **no se empaquetan** (`assets/faces`, vía `androidResources.ignoreAssetsPatterns`): comprobado listando el contenido del APK.
- `BuildConfig.FACES = false`. Sin esa función no hay herramienta en el menú de la biblioteca, ni franja «Personas» en Álbumes, ni fila en Ajustes, ni página en el tutorial, ni tema en la ayuda, ni rutas de navegación, y el trabajo de análisis termina sin hacer nada. Una prueba estática (`FacePrivacyGuardTest`) falla si alguien añade una entrada a la función sin comprobar el interruptor.
- La app es la misma en todo lo demás: galería, respaldo en S3, búsqueda por contenido, álbumes privados. ONNX Runtime se queda (lo usa la búsqueda por contenido).
- Lo que **no** cambia: el código de rostros sigue compilado y las tablas de Room v12 se crean igual (vacías y sin uso), de modo que la base de datos es idéntica entre ambas compilaciones y se puede pasar de una a otra sin migrar. Es un interruptor de producto, no una eliminación del código; si prefieres un árbol sin el código, bórralo con la guía de [sustitución del modelo](#el-modelo-elección-y-alternativas).

Tamaños medidos (release con R8, arm64, 26-27 de octubre de 2026):

| | Con «Personas» (por defecto) | `-PwithFaces=false` |
|---|---|---|
| APK (arm64) | 53 785 255 B (51,3 MiB) — medido el 26 de octubre, antes del interruptor | **35 523 220 B (33,9 MiB)** |
| AAB | 37 527 708 B (35,8 MiB) — ídem | **19 192 765 B (18,3 MiB)** |

Con esta compilación **no hay datos faciales** que declarar en Play ni sección «Datos faciales» que publicar en la política de privacidad ([lista de publicación](play-store/release-checklist.md#f-función-personas-rostros-bloqueos-específicos)).

## Limitaciones conocidas

- **Gemelos y parientes muy parecidos** se confunden (ver arriba); ningún umbral lo evita.
- **Cambios con la edad:** fotos de la misma persona con décadas de diferencia pueden no agruparse solas.
- Menores, perfiles, gafas de sol, mascarillas, oclusiones, fotos oscuras o borrosas: los rostros que no pasan la puerta de calidad no se comparan; los que la pasan con peor calidad bajan la confianza.
- Rostros pequeños en fotos de grupo grandes pueden no detectarse (entrada fija de 640 px).
- Una foto con rostros que no se pudieron describir cuenta como analizada (con un contador de «omitidos»); no hay botón para forzar el reintento de un rostro concreto.
- Los nombres de las personas se guardan sin cifrar en la base privada de la app.
- Separar un grupo es manual (elegir fotos y «Separar»); no hay propuesta automática de partición.
- El idioma de la interfaz es solo español (como el resto de la app).
- No se probó en un móvil ARM, en una tableta ni con una biblioteca de decenas de miles de fotos reales.

## Bloqueos antes de publicar en Google Play

Cómo se gestionan en este proyecto de código abierto (MIT): (1) el código y los modelos se distribuyen con sus licencias y un aviso de lo no verificado ([THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)); (2) la app y el README dicen que la función es experimental, que se ofrece sin garantía y que cada usuario es responsable de los permisos y leyes que le apliquen; (3) quien quiera publicar sin resolver los puntos de abajo puede compilar [sin la función](#compilar-sin-la-función). **Nada de esto sustituye una revisión legal** de quien publique una compilación con la función.

1. **Licencia y procedencia del modelo de reconocimiento (bloqueante hasta revisión legal).** SFace figura con licencia Apache-2.0 en OpenCV Zoo, pero el artículo original indica que se entrenó con CASIA-WebFace, VGGFace2 y/o MS-Celeb-1M, conjuntos de investigación cuyos términos no cubren, de forma explícita, el uso comercial de los pesos derivados (MS-Celeb-1M fue retirado). La licencia declarada por quien redistribuye el modelo no resuelve esa cuestión. Un abogado debe revisarlo; si la respuesta es negativa, el modelo se sustituye (la arquitectura lo permite: `MODEL_ID`, `PIPELINE_VERSION`, archivos en `assets/faces`) por otro con procedencia de datos clara.
2. **Datos biométricos y normativa.** Las huellas faciales con fines de identificar a una persona son dato biométrico (categoría especial en el RGPD, y objeto de leyes específicas en algunos países y estados). Que el procesamiento sea local y que el desarrollador no reciba nada puede cambiar quién es el responsable, pero **no** elimina por sí solo las obligaciones: lo debe valorar un profesional según los países donde se distribuya. No se ha hecho una evaluación de impacto ni una revisión legal.
3. **Política de privacidad y declaraciones.** Los borradores de `docs/play-store/` están actualizados con esta función, pero siguen **sin publicar ni revisar** y con los datos del responsable pendientes. En las páginas oficiales de Play consultadas (26 de octubre de 2026) «recopilar» es transmitir datos fuera del dispositivo y lo procesado solo en local no se declara; aun así, en esas páginas no hay una regla específica sobre datos biométricos, así que hay que comprobar el asistente de Play Console y la política de «Datos de usuario» vigente el día de enviar.
4. Siguen pendientes los bloqueos generales de `docs/play-store/release-checklist.md` (firma, cuenta, URL de la política, prueba en móvil físico).
5. **Prueba en un móvil ARM real y con una biblioteca grande** (rendimiento, batería, calor) y una revisión humana de calidad con fotos propias del propietario: no se hizo.
