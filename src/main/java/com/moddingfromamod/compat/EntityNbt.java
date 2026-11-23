package com.moddingfromamod.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * <b>An entity or block entity to and from NBT</b>. Since 1.21.6 an entity saves into a
 * {@code ValueOutput} and loads from a {@code ValueInput}, never a {@code CompoundTag} directly; the
 * repo reads and writes display and mob NBT in dozens of places (the rig's fields, a repair, a test's
 * round trip), so they all come through here. A problem the reporter would collect is dropped, as a
 * {@code CompoundTag} read always dropped it.
 */
public final class EntityNbt {

    private EntityNbt() {
    }

    /** What {@code com.moddingfromamod.compat.EntityNbt.save(e)} returned. */
    public static CompoundTag save(Entity e) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
        e.saveWithoutId(out);
        return out.buildResult();
    }

    /** What {@code e.save(tag)} did: the entity WITH its id into {@code into}; false for one that is not saved. */
    public static boolean saveWithId(Entity e, CompoundTag into) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
        boolean saved = e.save(out);
        if (saved) {
            into.merge(out.buildResult());
        }
        return saved;
    }

    /** What {@code e.load(tag)} did. */
    public static void load(Entity e, CompoundTag tag) {
        e.load(TagValueInput.create(ProblemReporter.DISCARDING, e.registryAccess(), tag));
    }

    /** What {@code stack.save(registries)} returned (an empty stack saves as an empty tag). */
    public static net.minecraft.nbt.Tag saveStack(net.minecraft.world.item.ItemStack stack, HolderLookup.Provider registries) {
        return net.minecraft.world.item.ItemStack.OPTIONAL_CODEC
                .encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack)
                .getOrThrow();
    }

    /** What {@code ItemStack.parse(registries, tag)} returned. */
    public static java.util.Optional<net.minecraft.world.item.ItemStack> parseStack(HolderLookup.Provider registries,
            net.minecraft.nbt.Tag tag) {
        return net.minecraft.world.item.ItemStack.CODEC
                .parse(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), tag).result();
    }

    /** What {@code effect.save()} returned. */
    public static net.minecraft.nbt.Tag saveEffect(net.minecraft.world.effect.MobEffectInstance e) {
        return net.minecraft.world.effect.MobEffectInstance.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, e)
                .getOrThrow();
    }

    /** What {@code MobEffectInstance.load(tag)} returned: null for an unreadable one. */
    public static net.minecraft.world.effect.MobEffectInstance loadEffect(net.minecraft.nbt.Tag tag) {
        return net.minecraft.world.effect.MobEffectInstance.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
                .result().orElse(null);
    }

    /** What {@code tag.hasUUID(key)} said: an int array of exactly four (UUIDUtil.CODEC's shape). */
    public static boolean hasUuid(CompoundTag tag, String key) {
        return tag.get(key) instanceof net.minecraft.nbt.IntArrayTag a && a.size() == 4;
    }

    /** What {@code tag.getUUID(key)} returned. */
    public static java.util.UUID getUuid(CompoundTag tag, String key) {
        return tag.read(key, net.minecraft.core.UUIDUtil.CODEC).orElseThrow();
    }

    /** What {@code be.loadWithComponents(tag, registries)} did. */
    public static void load(BlockEntity be, CompoundTag tag, HolderLookup.Provider registries) {
        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
    }
}
