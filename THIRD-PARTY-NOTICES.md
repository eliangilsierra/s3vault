# Avisos de terceros

S3 Vault se distribuye bajo la licencia MIT (ver [LICENSE](LICENSE)). Esa licencia cubre **el código de este repositorio**. Los modelos y bibliotecas de terceros que se listan aquí conservan **su propia licencia**; este archivo la indica y dice qué se sabe y qué no.

Esto es información, no asesoría legal.

## Función «Personas» (agrupación de rostros)

Los modelos viven en `app/src/main/assets/faces/`, junto al texto de su licencia. Se incluyen en el paquete **solo si** se compila con la función (el valor por defecto); con `-PwithFaces=false` no se empaquetan ([docs/face-grouping.md](docs/face-grouping.md#compilar-sin-la-función)).

| Componente | Archivo | Licencia | Titular / procedencia |
|---|---|---|---|
| **YuNet** (detector de rostros), versión 2023mar | `face_detection_yunet_2023mar.onnx` (0,23 MB) | MIT ([texto](app/src/main/assets/faces/LICENSE-yunet-MIT.txt)) | © 2020 Shiqi Yu. Distribuido por [OpenCV Zoo](https://github.com/opencv/opencv_zoo) |
| **SFace** (descriptor de rostros), versión 2021dec | `face_recognition_sface_2021dec_fp16w.onnx` (18,5 MiB) | Apache-2.0 ([texto](app/src/main/assets/faces/LICENSE-sface-Apache-2.0.txt)), según declara OpenCV Zoo | Zhong et al., «SFace: Sigmoid-Constrained Hypersphere Loss for Robust Face Recognition», IEEE TIP 2021. Distribuido por [OpenCV Zoo](https://github.com/opencv/opencv_zoo) |

**Modificación de SFace.** Los pesos en coma flotante de 32 bits se guardan en 16 bits y se convierten de vuelta al cargar (nodos `Cast`), y se limpian las entradas del grafo que sobraban. El resultado es numéricamente equivalente al original (similitud coseno ≥ 0,999994 entre ambas salidas en las pruebas) y el modelo pesa casi la mitad. No se reentrenó ni se afinó.

**Lo que NO está verificado: la procedencia de los datos de entrenamiento de SFace.** El artículo original indica que se entrenó con conjuntos de investigación (CASIA-WebFace y, según la variante, VGGFace2 y/o MS-Celeb-1M) cuyos términos no dicen de forma expresa que los pesos derivados puedan usarse libremente, y MS-Celeb-1M fue retirado por su autor. OpenCV Zoo declara Apache-2.0 para el modelo, pero esa declaración de quien lo redistribuye no resuelve por sí sola la cuestión de los datos con los que se entrenó. **Nadie ha hecho una revisión legal de esto.** Si te importa (por ejemplo, vas a redistribuir una compilación o a usarla con fines comerciales), compila con `-PwithFaces=false` o sustituye el modelo por otro cuya procedencia aceptes: el modelo está aislado (`FaceModel.kt`, `assets/faces/`) y el código no depende de que sea SFace.

Los modelos se ejecutan con ONNX Runtime (MIT, © Microsoft), que la aplicación ya incluía para la búsqueda por contenido.

## Fotografías de las pruebas

`app/src/test/resources/faces/` contiene fotografías **de dominio público** (obras del gobierno de EE. UU.: NASA, Fuerza Aérea, Senado) usadas solo por las pruebas; **no se empaquetan en la aplicación**. Procedencia y licencia mostrada en cada página de Wikimedia Commons: [ATTRIBUTION.md](app/src/test/resources/faces/ATTRIBUTION.md).

Para calibrar los umbrales se usó el conjunto *Labeled Faces in the Wild* **fuera del repositorio**: no se redistribuye ni se incluye en ninguna prueba.

## Responsabilidad de quien usa o redistribuye la aplicación

La licencia MIT ofrece el software «tal cual», sin garantía. Quien **use** la función de rostros es responsable de contar con el permiso de las personas que aparecen en sus fotos y de cumplir las leyes de su país sobre datos biométricos. Quien **redistribuya** una compilación (por ejemplo, en una tienda de aplicaciones) asume además las obligaciones propias de publicarla, incluida la revisión de las licencias de arriba. Ver el [aviso del README](README.md#aviso-y-responsabilidad).
