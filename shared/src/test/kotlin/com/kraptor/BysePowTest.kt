package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens for the shared Byse PoW solver, captured from Film1k's original
 * private implementation before it was deleted in favor of the shared
 * Extractorlar top-level version. Pins the ResolveURL byse.py hash port;
 * a regression in the hash or the leading-zero-bits loop fails red here.
 */
class BysePowTest {

    @Test fun `solvePow returns pinned solutions for live-shaped nonces`() {
        kotlinx.coroutines.runBlocking {
            // No d=20 golden here: its 1.8M-iteration search blew the default 20s
            // timeout on loaded CI runners (returned null -> red). The d=12/d=16
            // goldens pin the hash and leading-zero loop just as well.
            assertEquals("19", solvePow("deadbeef", 12))
            assertEquals("100367", solvePow("abc123", 16, timeoutSec = 120.0))
        }
    }

    @Test fun `solvePow short-circuits trivial difficulty`() {
        kotlinx.coroutines.runBlocking {
            assertEquals("0", solvePow("anything", 0))
        }
    }

    @Test fun `solvePow times out instead of looping forever`() {
        kotlinx.coroutines.runBlocking {
            // d=64 is unreachable by construction; must return null via the timeout path
            assertTrue(solvePow("unreachable", 64, timeoutSec = 0.05) == null)
        }
    }
}
