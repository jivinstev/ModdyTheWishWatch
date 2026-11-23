package com.moddingfromamod.species;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.entity.WishMobTags;

/**
 * <b>Makes a creature immune to its own abilities, mechanically</b>. {@link SelfHarm}
 * decides; this applies it, so the agent never has to remember.
 *
 * <ul>
 *   <li><b>At every datapack compile</b> ({@code DatapackForge.compile}, the one place
 *       {@code author_datapack}, {@code apply_effect} and {@code make_item} all pass): a datapack
 *       that names a wished creature and makes a hazard gives that creature the immunity. A
 *       creature that already exists is re-saved at once, which rebuilds its rig with the new
 *       power; one not sculpted yet gets it the moment it is.</li>
 *   <li><b>At every species write</b> ({@code SpeciesRegistry.put}): the immunities its datapacks
 *       call for are laid over what was written. A re-sculpt that rebuilds the species from the
 *       agent's own arguments would otherwise drop them, and Spikor would burn again on the next
 *       <i>"make him bigger"</i>.</li>
 *   <li><b>After a restart</b>: the stored datapack sources are read once, so what a datapack from
 *       yesterday asked for still holds.</li>
 * </ul>
 */
public final class SelfHarmGuard {

    /** species tag → the immunities the datapacks naming it call for. */
    private static final Map<String, Set<Species.Power>> BY_TAG = new ConcurrentHashMap<>();
    /** featureId → what the tool should tell the agent about that compile. */
    private static final Map<String, String> NOTES = new ConcurrentHashMap<>();
    /** Which datapack store has been read, so a world switch reads the new one. */
    private static volatile Path scanned;

    private SelfHarmGuard() {
    }

    /**
     * A datapack compiled: record what it asks for and protect the creatures it names now.
     *
     * @return the line for the agent; empty when nothing was added
     */
    public static String onCompiled(String featureId, String source) {
        Set<SelfHarm.Hazard> hazards = SelfHarm.hazardsIn(source);
        Set<String> tags = SelfHarm.creatureTags(source);
        if (hazards.isEmpty() || tags.isEmpty()) {
            NOTES.remove(featureId);
            return "";
        }
        Set<Species.Power> immune = SelfHarm.immunitiesFor(hazards);
        for (String tag : tags) {
            record(tag, immune);
        }
        StringBuilder note = new StringBuilder();
        var server = com.moddingfromamod.agent.AgentBridge.serverOrNull();
        for (Species sp : SpeciesRegistry.all()) {
            if (!tags.contains(WishMobTags.forSpecies(sp.id()))) {
                continue;
            }
            Set<Species.Power> missing = EnumSet.copyOf(immune);
            missing.removeAll(sp.powers());
            if (missing.isEmpty()) {
                continue;
            }
            if (server != null) {
                SpeciesRegistry.put(server, sp);   // guard() lays the immunity over it
            }
            if (note.length() > 0) {
                note.append('\n');
            }
            note.append(SelfHarm.note(sp.id(), missing));
        }
        NOTES.put(featureId, note.toString());
        return note.toString();
    }

    /** What the last compile of {@code featureId} made immune, for the tool's result. */
    public static String noteFor(String featureId) {
        String n = NOTES.get(featureId);
        return n == null ? "" : n;
    }

    /** Every species write passes here: the immunities its datapacks call for, laid over it. */
    static Species guard(Species sp) {
        if (sp == null) {
            return null;
        }
        scanStoreOnce();
        Set<Species.Power> need = BY_TAG.get(WishMobTags.forSpecies(sp.id()));
        return need == null ? sp : sp.withPowersAdded(need);
    }

    /** What the datapacks naming this species call for (for a test and for inspect_creature). */
    public static Set<Species.Power> immunitiesFor(String speciesId) {
        scanStoreOnce();
        Set<Species.Power> need = BY_TAG.get(WishMobTags.forSpecies(speciesId));
        return need == null ? Set.of() : Set.copyOf(need);
    }

    private static void record(String tag, Set<Species.Power> immune) {
        BY_TAG.merge(tag, EnumSet.copyOf(immune), (a, b) -> {
            Set<Species.Power> u = EnumSet.copyOf(a);
            u.addAll(b);
            return u;
        });
    }

    /** Read every stored datapack source once per store, so yesterday's datapacks still count. */
    private static void scanStoreOnce() {
        Path base;
        try {
            base = com.moddingfromamod.datapack.DatapackStore.baseDir();
        } catch (RuntimeException e) {
            return;   // no store configured (a unit test): nothing on disk to honour
        }
        if (base == null || base.equals(scanned)) {
            return;
        }
        synchronized (SelfHarmGuard.class) {
            if (base.equals(scanned)) {
                return;
            }
            for (String id : com.moddingfromamod.datapack.DatapackStore.listIds()) {
                Path src = com.moddingfromamod.datapack.DatapackStore.sourceFor(id);
                if (!Files.isRegularFile(src)) {
                    continue;   // a library zip: curated, and the library species set its own powers
                }
                try {
                    String source = Files.readString(src);
                    Set<SelfHarm.Hazard> hazards = SelfHarm.hazardsIn(source);
                    if (!hazards.isEmpty()) {
                        for (String tag : SelfHarm.creatureTags(source)) {
                            record(tag, SelfHarm.immunitiesFor(hazards));
                        }
                    }
                } catch (java.io.IOException | RuntimeException ignored) {
                    // an unreadable source protects nothing, and must never stop a species write
                }
            }
            scanned = base;
        }
    }
}
