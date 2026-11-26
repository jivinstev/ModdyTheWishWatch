package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moddingfromamod.store.SafeFileNameTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The handover and memory stores name a file after its artifact id, and an item's id is {@code id:<name>}. Before
 * {@link com.moddingfromamod.store.SafeFileName} that was {@code id:<name>.json}, which Windows refuses: reading it
 * threw inside a server task and a Builder edit of an item never finished there. Judged by Windows' rules on any OS.
 */
class StoreFileNamesTest {

    @Test
    @DisplayName("an item's handover record and memory are saved under names Windows accepts")
    void itemIdsAreWindowsSafe() {
        SafeFileNameTest.assertWindowsSafe(HandoverStore.fileName("id:edit_zap_lightning"));
        SafeFileNameTest.assertWindowsSafe(ArtifactMemoryStore.fileName("id:edit_zap_lightning"));
    }

    @Test
    @DisplayName("an id that was already a safe name keeps the file it always had")
    void plainIdsKeepTheirFiles() {
        assertEquals("freebie_df3440.json", HandoverStore.fileName("freebie_df3440"));
        assertEquals("spikor.json", ArtifactMemoryStore.fileName("spikor"));
    }
}
