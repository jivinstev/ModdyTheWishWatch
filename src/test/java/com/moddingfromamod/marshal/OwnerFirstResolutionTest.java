package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>his own things first</b>, in the catalogue the agent reads.
 *
 * <p>This file also pinned the resolver ladder's owner preference; the ladder was deleted in
 * (the agent names the referent itself), and what survives is the catalogue's ordering —
 * the agent's evidence of what is his.
 */
class OwnerFirstResolutionTest {

    private static final String ALICE = "1f2e3d4c-0000-0000-0000-000000000001";
    private static final String BOB = "9a8b7c6d-0000-0000-0000-000000000002";

    /**
     * {@code ownFirst} ORDERS and never drops. an earlier issue's recorded failure is a new wish read as a
     * modification, and ruling that out needs the whole world — hiding a brother's castle is how
     * "build me a castle" becomes a second castle three feet from the first one.
     */
    @Test
    void theCatalogueOrdersHisOwnFirstAndDropsNothing() {
        var entries = List.of(
                new WorldCatalogue.Entry("his", "build", "tower", List.of(), BOB),
                new WorldCatalogue.Entry("shared", "build", "hut", List.of(), ""),
                new WorldCatalogue.Entry("hers", "build", "castle", List.of(), ALICE));
        var out = WorldCatalogue.ownFirst(ALICE, entries);
        assertEquals(List.of("hers", "shared", "his"), out.stream()
                .map(WorldCatalogue.Entry::id).toList());
        assertEquals(entries.size(), out.size(), "nothing may be dropped from the catalogue");
    }

    @Test
    void withNoAskerTheCatalogueOrderIsUntouched() {
        var entries = List.of(
                new WorldCatalogue.Entry("his", "build", "tower", List.of(), BOB),
                new WorldCatalogue.Entry("hers", "build", "castle", List.of(), ALICE));
        assertEquals(entries, WorldCatalogue.ownFirst("", entries));
    }
}
