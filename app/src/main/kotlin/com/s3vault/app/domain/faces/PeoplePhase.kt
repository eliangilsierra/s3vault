package com.s3vault.app.domain.faces

/** What the People screen has to say, in the order of how much it matters. Each one has its own message and its own way forward. */
enum class PeoplePhase {
    /** There are no photos on the device to analyse. */
    NO_PHOTOS,

    /** The user has not turned the analysis on (or turned it off): nothing was or is being analysed. */
    NOT_STARTED,

    /** The two models that ship with the app cannot be used (damaged install). */
    MODEL_UNAVAILABLE,

    /** The key that seals the index is lost: what is stored cannot be read and must be rebuilt. */
    INDEX_UNREADABLE,

    /** A first analysis is running or waiting for the charger / battery, and nothing is to show yet. */
    ANALYZING,

    /** Faces were found, but not enough of the same person to form a group the app is willing to show. */
    NO_RELIABLE_GROUPS,

    /** The analysis was finished with some pictures the app could not read. Groups are shown, with a note. */
    INCOMPLETE,

    /** The analysis was made by an older version of the pipeline; it is being redone and the groups shown are the old ones. */
    OUTDATED,

    /** Groups to show. */
    READY,
}

/** The facts the phase is decided from. Plain numbers, so the decision is testable without Android. */
data class PeopleFacts(
    val enabled: Boolean,
    val images: Int,
    val analyzed: Int,
    val pending: Int,
    val unreadable: Int,
    val stale: Int,
    val faces: Int,
    val groups: Int,
    val running: Boolean,
    val modelAvailable: Boolean,
    val indexReadable: Boolean,
)

object PeoplePhases {
    fun resolve(f: PeopleFacts): PeoplePhase = when {
        f.images == 0 && f.groups == 0 -> PeoplePhase.NO_PHOTOS
        !f.enabled -> if (f.groups > 0) PeoplePhase.READY else PeoplePhase.NOT_STARTED
        !f.modelAvailable -> PeoplePhase.MODEL_UNAVAILABLE
        !f.indexReadable -> PeoplePhase.INDEX_UNREADABLE
        f.groups > 0 && f.stale > 0 -> PeoplePhase.OUTDATED
        f.groups > 0 && f.unreadable > 0 && f.pending == 0 -> PeoplePhase.INCOMPLETE
        f.groups > 0 -> PeoplePhase.READY
        f.pending > 0 || f.running -> PeoplePhase.ANALYZING
        f.faces > 0 -> PeoplePhase.NO_RELIABLE_GROUPS
        // Everything analysed and nobody found: say so as "no reliable groups" with zero faces handled by the screen's text.
        f.analyzed > 0 -> PeoplePhase.NO_RELIABLE_GROUPS
        else -> PeoplePhase.ANALYZING
    }
}
