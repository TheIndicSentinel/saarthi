package com.saarthi.core.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRangePolicyTest {

    @Test
    fun `rejects tiny local files even when the server total matches`() {
        assertFalse(
            DownloadRangePolicy.shouldAcceptAsComplete(
                localBytes = 500_000L,
                serverTotalBytes = 500_000L,
                catalogFileSizeBytes = 500_000L,
            ),
        )
    }

    @Test
    fun `rejects when local is shorter than the server Content-Range total`() {
        assertFalse(
            DownloadRangePolicy.shouldAcceptAsComplete(
                localBytes = 2_000_000L,
                serverTotalBytes = 5_000_000L,
                catalogFileSizeBytes = 5_000_000L,
            ),
        )
    }

    @Test
    fun `rejects 416 complete relative to server when the catalog is much larger`() {
        // The field leftover: catalog 2.5 GB, server 416-total 1.5 GB, local
        // matches the server — previously accepted, then isFileComplete failed.
        assertFalse(
            DownloadRangePolicy.shouldAcceptAsComplete(
                localBytes = 1_500_000_000L,
                serverTotalBytes = 1_500_000_000L,
                catalogFileSizeBytes = 2_500_000_000L,
            ),
        )
    }

    @Test
    fun `accepts when local covers the server and is within 85 percent of the catalog`() {
        assertTrue(
            DownloadRangePolicy.shouldAcceptAsComplete(
                localBytes = 2_400_000_000L,
                serverTotalBytes = 2_400_000_000L,
                catalogFileSizeBytes = 2_500_000_000L,
            ),
        )
    }

    @Test
    fun `with no catalog size, local covering the server is accepted`() {
        assertTrue(
            DownloadRangePolicy.shouldAcceptAsComplete(
                localBytes = 3_000_000L,
                serverTotalBytes = 3_000_000L,
                catalogFileSizeBytes = null,
            ),
        )
    }

    @Test
    fun `with no server total, catalog 85 percent bar still applies`() {
        assertFalse(
            DownloadRangePolicy.shouldAcceptAsComplete(
                localBytes = 1_500_000_000L,
                serverTotalBytes = null,
                catalogFileSizeBytes = 2_500_000_000L,
            ),
        )
        assertTrue(
            DownloadRangePolicy.shouldAcceptAsComplete(
                localBytes = 2_400_000_000L,
                serverTotalBytes = null,
                catalogFileSizeBytes = 2_500_000_000L,
            ),
        )
    }
}
