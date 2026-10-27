package com.s3vault.app.domain.faces

import org.junit.Assert.assertEquals
import org.junit.Test

class PeoplePhaseTest {
    private fun facts(
        enabled: Boolean = true, images: Int = 100, analyzed: Int = 100, pending: Int = 0, unreadable: Int = 0, stale: Int = 0,
        faces: Int = 50, groups: Int = 3, running: Boolean = false, model: Boolean = true, readable: Boolean = true,
    ) = PeopleFacts(enabled, images, analyzed, pending, unreadable, stale, faces, groups, running, model, readable)

    private fun phase(f: PeopleFacts) = PeoplePhases.resolve(f)

    @Test fun `no photos on the device`() = assertEquals(PeoplePhase.NO_PHOTOS, phase(facts(images = 0, analyzed = 0, faces = 0, groups = 0)))
    @Test fun `analysis not turned on`() = assertEquals(PeoplePhase.NOT_STARTED, phase(facts(enabled = false, analyzed = 0, faces = 0, groups = 0)))
    @Test fun `first analysis in progress`() = assertEquals(PeoplePhase.ANALYZING, phase(facts(analyzed = 10, pending = 90, faces = 4, groups = 0, running = true)))
    @Test fun `waiting for the charger counts as analysing`() = assertEquals(PeoplePhase.ANALYZING, phase(facts(analyzed = 0, pending = 100, faces = 0, groups = 0)))
    @Test fun `faces found but no reliable groups`() = assertEquals(PeoplePhase.NO_RELIABLE_GROUPS, phase(facts(faces = 12, groups = 0)))
    @Test fun `analysed everything and found nobody`() = assertEquals(PeoplePhase.NO_RELIABLE_GROUPS, phase(facts(faces = 0, groups = 0)))
    @Test fun `groups found`() = assertEquals(PeoplePhase.READY, phase(facts()))
    @Test fun `groups found while the rest goes on`() = assertEquals(PeoplePhase.READY, phase(facts(pending = 30, running = true)))
    @Test fun `groups with unreadable pictures`() = assertEquals(PeoplePhase.INCOMPLETE, phase(facts(unreadable = 4)))
    @Test fun `unreadable pictures are not the headline while work remains`() = assertEquals(PeoplePhase.READY, phase(facts(unreadable = 4, pending = 10)))
    @Test fun `an older analysis being redone`() = assertEquals(PeoplePhase.OUTDATED, phase(facts(stale = 20, pending = 20)))
    @Test fun `models that cannot be used`() = assertEquals(PeoplePhase.MODEL_UNAVAILABLE, phase(facts(model = false)))
    @Test fun `an index that cannot be read`() = assertEquals(PeoplePhase.INDEX_UNREADABLE, phase(facts(readable = false)))
    @Test fun `turned off but groups remain are still shown`() = assertEquals(PeoplePhase.READY, phase(facts(enabled = false)))
}
