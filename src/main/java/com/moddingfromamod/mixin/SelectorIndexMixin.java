package com.moddingfromamod.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moddingfromamod.selector.IndexedLookup;
import com.moddingfromamod.selector.SelectorIndex;
import com.moddingfromamod.selector.TagIndex;
import com.moddingfromamod.selector.TaggedSelector;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * <b>{@code @e[tag=X]} with no position is answered from the entities carrying X</b>.
 *
 * <p>Wraps {@code EntitySelector.addEntities}, the one place an entity selector collects from a
 * level. With no box ({@code aabb == null}) vanilla walks every entity of the level's visible
 * lookup in its linked order; this walks only the {@link TagIndex} candidates of the selector's
 * rarest positive tag, in the SAME order, and applies vanilla's own type test, predicate (every
 * option, every tag again) and result limit exactly as {@code ServerLevel.getEntities} does. With a
 * box, no positive tag, or a lookup it cannot index, vanilla answers untouched.
 *
 * <p>{@link SelectorIndex.Mode#VERIFY}: the index answers into a scratch list, vanilla runs as
 * normal, and any difference in which entities or in what order is a counted, logged mismatch.
 * Vanilla's answer is the one used.
 */
@Mixin(EntitySelector.class)
public abstract class SelectorIndexMixin implements TaggedSelector {

    @Shadow
    @Final
    private EntityTypeTest<Entity, ?> type;

    @Shadow
    private int getResultLimit() {
        throw new AssertionError("mixin shadow");
    }

    @Unique
    private List<String> moddingfromamod$tags = List.of();

    @Unique
    private String moddingfromamod$text = "";

    @Override
    public List<String> moddingfromamod$tags() {
        return moddingfromamod$tags;
    }

    @Override
    public String moddingfromamod$text() {
        return moddingfromamod$text;
    }

    @Override
    public void moddingfromamod$setTags(List<String> tags, String text) {
        moddingfromamod$tags = tags == null ? List.of() : tags;
        moddingfromamod$text = text == null ? "" : text;
    }

    @WrapMethod(method = "addEntities")
    private void moddingfromamod$indexed(List<Entity> out, ServerLevel level, AABB box,
                                         Predicate<Entity> predicate, Operation<Void> original) {
        if (box != null || SelectorIndex.mode() == SelectorIndex.Mode.OFF) {
            original.call(out, level, box, predicate);   // vanilla already searches only the box
            return;
        }
        TagIndex<Entity> index = null;
        if (!moddingfromamod$tags.isEmpty()
                && level.getEntities() instanceof LevelEntityGetterAdapterAccessor adapter
                && adapter.moddingfromamod$visibleEntities() instanceof IndexedLookup lookup
                && !lookup.moddingfromamod$poisoned()) {
            index = lookup.moddingfromamod$tagIndex();
        }
        if (index == null) {
            SelectorIndex.NOT_INDEXABLE.incrementAndGet();
            original.call(out, level, box, predicate);
            return;
        }
        SelectorIndex.INDEXED.incrementAndGet();
        if (SelectorIndex.mode() == SelectorIndex.Mode.VERIFY) {
            int before = out.size();
            List<Entity> mine = new ArrayList<>(out);
            moddingfromamod$collect(mine, index, predicate);
            original.call(out, level, box, predicate);
            List<Entity> theirs = out.subList(before, out.size());
            List<Entity> ours = mine.subList(before, mine.size());
            if (!moddingfromamod$sameEntities(theirs, ours)) {
                SelectorIndex.mismatch(moddingfromamod$text + " in " + level.dimension().location(),
                        describe(theirs), describe(ours));
            }
            return;
        }
        moddingfromamod$collect(out, index, predicate);
    }

    /** {@code ServerLevel.getEntities(type, predicate, out, limit)}, over the candidates only. */
    @Unique
    private void moddingfromamod$collect(List<Entity> out, TagIndex<Entity> index, Predicate<Entity> predicate) {
        int limit = getResultLimit();
        if (out.size() >= limit) {
            return;   // addEntities' own first check
        }
        List<Entity> candidates = index.candidates(moddingfromamod$tags);
        SelectorIndex.CANDIDATES.addAndGet(candidates.size());
        for (Entity e : candidates) {
            Entity cast = type.tryCast(e);
            if (cast != null && predicate.test(cast)) {
                out.add(cast);
                if (out.size() >= limit) {
                    return;
                }
            }
        }
    }

    @Unique
    private static boolean moddingfromamod$sameEntities(List<Entity> a, List<Entity> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private static String describe(List<Entity> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size() && i < 8; i++) {
            Entity e = l.get(i);
            sb.append(i == 0 ? "" : ", ").append(e.getType().toShortString()).append('#').append(e.getId());
        }
        return sb.append(l.size() > 8 ? ", … " + l.size() + "]" : "]").toString();
    }
}
