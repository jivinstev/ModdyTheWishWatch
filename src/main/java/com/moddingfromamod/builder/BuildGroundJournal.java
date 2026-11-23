package com.moddingfromamod.builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.EventJournal;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What a building was built ON, so taking it down gives it back</b>. A walkthrough's
 * farm plot went up where the player looked — on his cobblestone generator — and cleared its lava; until
 * this, {@link Builder#clearBuild} set every position to air, so an undo gave back a hole.
 *
 * <p>The events' own journal ({@link EventJournal}), one per building, kept per world (each dimension's data):
 * <ul>
 *   <li>every block a building replaces that was NOT air is recorded as it goes up ({@link Builder}'s drain) — air needs
 *       no record, which keeps a big build on open ground cheap;</li>
 *   <li>the first prior wins, so an edit that re-renders the building in place still remembers the ground from before
 *       its FIRST version (the take-down of the old version gives that ground back, and the new one records it again);</li>
 *   <li>a cell is given back only if it still holds what the building wrote: one the player changed since is left as he
 *       left it ({@link #takeBack}).</li>
 * </ul>
 * A building raised before this has no record and comes down to air, as it always did: nothing is shimmed
 * (docs/COMPAT_SHIMS.md). Past {@link #MAX_CELLS} a building records no more, and the rest of it comes down to air.
 */
public final class BuildGroundJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger("mfam-build");
    private static final String DATA = ModdingFromAModMod.MODID + "_build_ground";

    /** The events' own ceiling (docs/EVENTS_PLAN.md D2): enough for any real build's non-air ground. */
    public static final int MAX_CELLS = 250_000;

    private BuildGroundJournal() {
    }

    /** What a take-down does with one cell. */
    public enum Back { RESTORE, LEAVE, CLEAR }

    /** One cell's answer: what to do, and the state to put back when it is {@link Back#RESTORE}. */
    public record Answer(Back what, BlockState state) {
    }

    /** The per-world store: one journal per building id. */
    public static final class Store extends SavedData {
        public static final int VERSION = 1;
        final Map<String, EventJournal<BlockState>> byBuild = new LinkedHashMap<>();
        /** A copy written by a newer build: record nothing, give nothing back. */
        String refused;

        static Store load(CompoundTag tag, HolderLookup.Provider lookup) {
            Store s = new Store();
            int v = tag.getIntOr("v", 0);
            if (v > VERSION) {
                s.refused = "the build ground was saved by a newer Moddy (v" + v + ")";
                LOGGER.warn("[build-ground] {}; it is left untouched", s.refused);
                return s;
            }
            HolderGetter<Block> blocks = lookup.lookupOrThrow(Registries.BLOCK);
            ListTag list = tag.getListOrEmpty("builds");
            for (int i = 0; i < list.size(); i++) {
                try {
                    CompoundTag t = list.getCompoundOrEmpty(i);
                    List<CompoundTag> palette = new java.util.ArrayList<>();
                    ListTag p = t.getListOrEmpty("palette");
                    for (int k = 0; k < p.size(); k++) {
                        palette.add(p.getCompoundOrEmpty(k));
                    }
                    s.byBuild.put(t.getStringOr("id", ""), EventJournal.unflatten(new EventJournal.Flat<>(palette,
                                    t.getLongArray("pos").orElseGet(() -> new long[0]),
                                    t.getIntArray("prior").orElseGet(() -> new int[0]),
                                    t.getIntArray("written").orElseGet(() -> new int[0])),
                            c -> NbtUtils.readBlockState(blocks, c)));
                } catch (RuntimeException ex) {
                    // One damaged record must not cost every other building its ground.
                    LOGGER.warn("[build-ground] an unreadable building's ground was skipped: {}", ex.toString());
                }
            }
            return s;
        }

        public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
            tag.putInt("v", VERSION);
            ListTag list = new ListTag();
            for (var e : byBuild.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                CompoundTag t = new CompoundTag();
                t.putString("id", e.getKey());
                EventJournal.Flat<CompoundTag> flat = e.getValue().flatten(NbtUtils::writeBlockState);
                ListTag palette = new ListTag();
                palette.addAll(flat.palette());
                t.put("palette", palette);
                t.putLongArray("pos", flat.pos());
                t.putIntArray("prior", flat.prior());
                t.putIntArray("written", flat.written());
                list.add(t);
            }
            tag.put("builds", list);
            return tag;
        }

        static net.minecraft.world.level.saveddata.SavedDataType<Store> factory() {
            return com.moddingfromamod.compat.SavedDataTypes.of(DATA, Store::new, Store::load,
                    (s, lookup) -> s.save(new CompoundTag(), lookup));
        }
    }

    static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Store.factory());
    }

    /** A building is about to put {@code written} where {@code prior} stands. Server thread. */
    static void record(ServerLevel level, String buildId, long pos, BlockState prior, BlockState written) {
        if (buildId == null || buildId.isEmpty() || prior.isAir() || prior == written) {
            return;
        }
        Store s = store(level);
        if (s.refused != null) {
            return;
        }
        EventJournal<BlockState> j = s.byBuild.computeIfAbsent(buildId, k -> new EventJournal<>());
        if (!j.holds(pos) && j.size() >= MAX_CELLS) {
            return;
        }
        j.record(pos, prior, written);
        s.setDirty();
    }

    /** It wrote over its own block at {@code pos}: the ground stays what it was, the building's block is now this. */
    static void rewrote(ServerLevel level, String buildId, long pos, BlockState written) {
        if (buildId == null || buildId.isEmpty()) {
            return;
        }
        Store s = store(level);
        EventJournal<BlockState> j = s.refused != null ? null : s.byBuild.get(buildId);
        if (j != null && j.holds(pos)) {
            j.record(pos, null, written);
            s.setDirty();
        }
    }

    /**
     * What a take-down does with the cell at {@code pos}, now holding {@code current}: give back what the building
     * replaced (it is still what we wrote), leave it (he changed it since), or clear it to air (no record: it was air,
     * or the building predates this). Forgets the cell either way.
     */
    static Answer takeBack(ServerLevel level, String buildId, long pos, BlockState current) {
        Store s = buildId == null ? null : store(level);
        EventJournal<BlockState> j = s == null || s.refused != null ? null : s.byBuild.get(buildId);
        if (j == null || !j.holds(pos)) {
            return new Answer(current.isAir() ? Back.LEAVE : Back.CLEAR, null);
        }
        BlockState back = j.release(pos, current);
        s.setDirty();
        if (j.isEmpty()) {
            s.byBuild.remove(buildId);
        }
        return back == null ? new Answer(Back.LEAVE, null) : new Answer(Back.RESTORE, back);
    }

    /** How many cells this building's ground holds (a test's look). */
    public static int size(ServerLevel level, String buildId) {
        EventJournal<BlockState> j = store(level).byBuild.get(buildId);
        return j == null ? 0 : j.size();
    }
}
