package com.kraptor

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Self-check for the shared DistinctBar helper; runs with every provider's test task. */
class DistinctBarTest {

    @Test fun `distinct videos pass`() {
        DistinctBar.assertDistinctVideos(
            listOf(
                DistinctBar.VideoIdentity("Title A", "/p/a.jpg", "/v/a"),
                DistinctBar.VideoIdentity("Title B", "/p/b.jpg", "/v/b"),
            )
        )
    }

    @Test fun `repeated field values fail`() {
        for (mutate in listOf<(DistinctBar.VideoIdentity) -> DistinctBar.VideoIdentity>(
            { it.copy(title = "Title A") },
            { it.copy(poster = "/p/a.jpg") },
            { it.copy(url = "/v/a") },
        )) {
            try {
                DistinctBar.assertDistinctVideos(
                    listOf(
                        DistinctBar.VideoIdentity("Title A", "/p/a.jpg", "/v/a"),
                        mutate(DistinctBar.VideoIdentity("Title B", "/p/b.jpg", "/v/b")),
                    )
                )
                fail("expected collision to fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Distinct bar"))
            }
        }
    }

    @Test fun `normalization collapses case and whitespace, nulls are skipped`() {
        DistinctBar.assertDistinctVideos(
            listOf(
                DistinctBar.VideoIdentity("Title A", null, null),
                DistinctBar.VideoIdentity("Title B", null, null),
            )
        )  // null fields skipped
        try {
            DistinctBar.assertDistinctVideos(
                listOf(
                    DistinctBar.VideoIdentity("Title A", "/p/a.jpg", "/v/a"),
                    DistinctBar.VideoIdentity("  title A ", "/p/b.jpg", "/v/b"),
                )
            )
            fail("expected case-insensitive title collision to fail")
        } catch (_: IllegalStateException) {
        }
    }
}
