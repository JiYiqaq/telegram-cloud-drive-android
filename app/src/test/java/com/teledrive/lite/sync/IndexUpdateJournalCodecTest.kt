package com.teledrive.lite.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IndexUpdateJournalCodecTest {
    @Test
    fun canonicalRoundTripPreservesFrozenOperationIdsAndRemoteDocument() {
        val journal = IndexUpdateJournal(
            operationId = "00000000-0000-0000-0000-000000000010",
            baseStableState = StableIndexState(3, 30, "old-file"),
            candidateRevision = 4,
            phase = IndexUpdatePhase.FINAL_EDITED,
            provisionalMessageId = 40,
            finalDocument = RemoteIndexDocument(
                40,
                "new-file",
                IndexAtomicUpdater.INDEX_FILE_NAME,
                123,
            ),
            includedOperationIds = setOf("b", "a"),
        )

        val encoded = IndexUpdateJournalCodec.encode(journal)

        assertEquals(journal, IndexUpdateJournalCodec.decode(encoded))
        assertEquals(encoded, IndexUpdateJournalCodec.encode(IndexUpdateJournalCodec.decode(encoded)))
    }

    @Test
    fun rejectsUnknownOrNonCanonicalJournalJson() {
        assertThrows(IllegalArgumentException::class.java) {
            IndexUpdateJournalCodec.decode("{}")
        }
        val canonical = IndexUpdateJournalCodec.encode(
            IndexUpdateJournal("op", StableIndexState.empty(), 1, IndexUpdatePhase.PREPARED),
        )
        assertThrows(IllegalArgumentException::class.java) {
            IndexUpdateJournalCodec.decode(canonical.replaceFirst("{", "{\"unknown\":1,"))
        }
    }
}
