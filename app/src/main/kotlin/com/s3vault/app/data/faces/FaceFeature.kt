package com.s3vault.app.data.faces

import com.s3vault.app.BuildConfig

/**
 * Whether this build carries the face feature at all. It is a build-time choice (`-PwithFaces=false` leaves out the models and every
 * way into the screens), so a distributor can ship the gallery and the S3 backup without it. The default is on.
 */
object FaceFeature {
    val available: Boolean get() = BuildConfig.FACES
}
