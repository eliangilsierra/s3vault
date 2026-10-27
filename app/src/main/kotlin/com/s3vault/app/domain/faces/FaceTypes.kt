package com.s3vault.app.domain.faces

/** Whether a photo's analysis produced a result, or the file could not be read. */
enum class FacePhotoStatus { DONE, UNREADABLE }

/** How a face came to belong (or not) to a person. */
enum class FaceAssignment {
    /** In no group yet. */
    NONE,

    /** Placed by the grouping because it is very close to the group. Counted as a probable match, never as confirmed. */
    AUTO,

    /** The grouping thinks it may be this person but is not sure: waits for the user's yes or no and belongs to nobody meanwhile. */
    SUGGESTED,

    /** The user said so (accepted a suggestion, moved it, merged or confirmed the group). Never changed by the grouping. */
    CONFIRMED,
}

/** What the user has done with a group. */
enum class PersonState {
    /** Formed by the grouping; the user has not looked at it. */
    PROVISIONAL,

    /** The user named it, confirmed it or merged into it. The grouping never merges or splits it on its own. */
    CONFIRMED,

    /** The user chose "no sugerir más": faces that match it join silently, are not shown, and no rule can use it. */
    HIDDEN,
}

enum class RuleState { ACTIVE, PAUSED }

/** What the user chose to cover when they turned the rule on. */
enum class RuleScope {
    /** Only photos that enter the library after the rule was turned on. */
    NEW_ONLY,

    /** Also the photos already on the device that match and are not backed up. The user saw the count first. */
    INCLUDE_EXISTING,
}

/** How sure a match must be for a photo to be queued without asking. */
enum class RulePolicy {
    /** Default. Only matches the user confirmed, or automatic ones that are very close, clear of any other person, and of good quality. */
    STRICT,

    /** The user accepted that probable matches (not confirmed) are queued too. Ambiguous ones still go to review. */
    INCLUDE_PROBABLE,
}

enum class DecisionOutcome {
    /** The rule put the photo in the backup queue. */
    QUEUED,

    /** The rule would not decide: the photo waits in the review list for the user. */
    REVIEW,

    /** The user looked at it and said no. Not offered again for this person. */
    DISMISSED,
}

enum class DecisionReason { NONE, AMBIGUOUS, LOW_CONFIDENCE, GROUP_NOT_CONFIRMED, LOW_QUALITY, USER }
