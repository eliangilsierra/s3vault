package com.s3vault.app.domain.faces

/**
 * What the face pipeline is made of, in one place. The detector and the recogniser are two small ONNX models that ship inside the
 * app (see docs/face-grouping.md for licences, hashes and measurements), so the analysis needs no download and no network.
 *
 * Two numbers decide when stored results stop being valid:
 *  - [MODEL_ID]: the embedding space. Vectors of different ids are never compared with each other.
 *  - [PIPELINE_VERSION]: everything else that changes what a picture yields (detector thresholds, quality gate, working
 *    resolution). A photo analysed under an older version is analysed again, keeping what the user corrected (see FaceReconciler).
 */
object FaceModel {
    const val DETECTOR = "yunet-2023mar"
    const val RECOGNIZER = "sface-2021dec-int8"
    const val MODEL_ID = "$DETECTOR+$RECOGNIZER"
    const val PIPELINE_VERSION = 1

    /** YuNet's fixed input: a 640 x 640 canvas, BGR, raw 0..255. */
    const val DETECT_SIZE = 640

    /** SFace's input: a 112 x 112 face aligned to [TEMPLATE], RGB, raw 0..255 (it normalises inside). */
    const val ALIGNED = 112
    const val DIMENSION = 128

    /** Longest side of the decoded working picture: faces are cropped from it, and it is reduced to [DETECT_SIZE] for detection. */
    const val WORKING_SIDE = 1280

    /** Where SFace expects the five landmarks (eyes, nose tip, mouth corners) in a 112 x 112 crop: the ArcFace template, x then y. */
    val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f, // eye on the left of the picture
        73.5318f, 51.5014f, // eye on the right of the picture
        56.0252f, 71.7366f, // nose tip
        41.5493f, 92.3655f, // mouth corner on the left of the picture
        70.7299f, 92.2041f, // mouth corner on the right of the picture
    )
}

/** One face as the detector reports it, in the pixel space of the picture it was given. [landmarks] is x0,y0 ... x4,y4. */
class RawFace(val score: Float, val x: Float, val y: Float, val w: Float, val h: Float, val landmarks: FloatArray) {
    val centerX: Float get() = x + w / 2
    val centerY: Float get() = y + h / 2
}

/** The numbers every face gets measured with. Kept in one object so the calibration (docs/face-grouping.md) has one home. */
data class FaceThresholds(
    /** YuNet score under which a detection is ignored. */
    val detection: Float = 0.7f,
    /** Overlap above which two detections are the same face. */
    val nms: Float = 0.3f,
    /** Distance between the eyes, in pixels of the working picture, under which a face is too small to be told from others. */
    val minEyeDistance: Float = 20f,
    /** Variance of the Laplacian of the aligned face (0..255 scale) under which it is too blurry. */
    val minSharpness: Float = 25f,
    val minBrightness: Float = 35f,
    val maxBrightness: Float = 235f,
    /** Nose offset from the eye midpoint, over the eye distance: above this the head is turned too far (profile). */
    val maxYaw: Float = 0.55f,
    /** Degrees the eye line may be tilted. */
    val maxRoll: Float = 45f,
)
