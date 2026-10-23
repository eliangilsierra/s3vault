package com.s3vault.app.domain

import com.s3vault.app.domain.SyncState.*

/**
 * Allowed transitions of [SyncState]. Every state change in the data layer goes through [require],
 * so an illegal jump (e.g. LOCAL_ONLY -> BACKED_UP without verification) is a bug, not a silent write.
 *
 * Recovery after process death: UPLOADING and VERIFYING return to QUEUED (see [recoveryTarget]). Nothing is ever marked BACKED_UP before VERIFYING succeeded.
 */
object SyncStateMachine {
    private val allowed: Map<SyncState, Set<SyncState>> = mapOf(
        // "-> REMOTE_ONLY" from the local states = the local file vanished from MediaStore while a verified remote
        // copy exists. The remote copy is never deleted in response.
        LOCAL_ONLY to setOf(QUEUED, REMOTE_ONLY),
        QUEUED to setOf(UPLOADING, LOCAL_ONLY, FAILED, REMOTE_ONLY, VERIFYING /* already present remotely */),
        UPLOADING to setOf(VERIFYING, QUEUED, FAILED),
        VERIFYING to setOf(BACKED_UP, QUEUED, FAILED),
        // A backed-up file that changed locally becomes LOCAL_ONLY again (new version needed).
        BACKED_UP to setOf(LOCAL_ONLY, REMOTE_ONLY),
        FAILED to setOf(QUEUED, LOCAL_ONLY, REMOTE_ONLY),
        REMOTE_ONLY to setOf(RESTORING, BACKED_UP /* file reappeared locally */),
        RESTORING to setOf(REMOTE_ONLY, BACKED_UP),
    )

    fun canTransition(from: SyncState, to: SyncState): Boolean = from == to || to in allowed.getValue(from)

    fun require(from: SyncState, to: SyncState) {
        check(canTransition(from, to)) { "Illegal sync transition $from -> $to" }
    }

    /** State an interrupted (in-flight) item must be reset to when the process restarts. */
    fun recoveryTarget(state: SyncState): SyncState? = when (state) {
        UPLOADING, VERIFYING -> QUEUED
        // RESTORING rows of the remote library are the persistent restore queue (WorkManager owns the work), so
        // they are NOT reset on restart; the user can cancel them explicitly.
        RESTORING -> null
        else -> null
    }

    /** States in which a worker is expected to be actively transferring. */
    val inFlight: Set<SyncState> = setOf(UPLOADING, VERIFYING, RESTORING)
}
