package com.moddingfromamod.compat;

import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * <b>A world-saved store</b>. 1.21.5 replaced {@code SavedData.Factory(new, load, fixer)} plus a
 * file name with one {@code SavedDataType(id, new, codec, fixer)}; a store is written by a Codec rather
 * than an overridden {@code save}. This builds that Codec from the store's existing
 * {@code load(tag, lookup)} / {@code save(tag, lookup)}, so the file on disk keeps the same bytes.
 */
public final class SavedDataTypes {

    private SavedDataTypes() {
    }

    public static <T extends SavedData> SavedDataType<T> of(String id, Supplier<T> fresh,
            BiFunction<CompoundTag, HolderLookup.Provider, T> load,
            BiFunction<T, HolderLookup.Provider, CompoundTag> save) {
        return new SavedDataType<>(id, ctx -> fresh.get(), ctx -> {
            HolderLookup.Provider lookup = ctx.levelOrThrow().registryAccess();
            return CompoundTag.CODEC.xmap(tag -> load.apply(tag, lookup), t -> save.apply(t, lookup));
        }, null);
    }
}
