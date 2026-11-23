package com.moddingfromamod.mixin;

import com.moddingfromamod.selector.ObservedTagSet;
import com.moddingfromamod.selector.SelectorIndex;
import com.moddingfromamod.selector.TagIndex;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>Each entity lookup keeps a {@link TagIndex} of what it holds</b>, fed by its own
 * {@code add} and {@code remove} — so membership, and order, are this map's and nobody's guess.
 *
 * <p>Decided AFTER vanilla's own write, by asking {@code byId} what it now holds: {@code add} refuses
 * a duplicate UUID without inserting (1.21.8 bytecode), and the index must refuse it too. Vanilla
 * walks {@code byId}, a linked map, so insertion order is the order {@code @e} returns entities in.
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupIndexMixin<T extends EntityAccess> implements com.moddingfromamod.selector.IndexedLookup {

    @Shadow
    @Final
    private Int2ObjectMap<T> byId;

    @Unique
    private final TagIndex<Entity> moddingfromamod$index = new TagIndex<>();

    @Unique
    private boolean moddingfromamod$poisoned;

    @Override
    public TagIndex<Entity> moddingfromamod$tagIndex() {
        return moddingfromamod$index;
    }

    @Override
    public boolean moddingfromamod$poisoned() {
        return moddingfromamod$poisoned;
    }

    @Inject(method = "add", at = @At("TAIL"))
    private void moddingfromamod$added(T t, CallbackInfo ci) {
        if (!(t instanceof Entity e) || byId.get(e.getId()) != t || moddingfromamod$index.contains(e)) {
            return;
        }
        if (!(e.getTags() instanceof ObservedTagSet tags)) {
            moddingfromamod$poison(e);   // an entity this build did not construct: never guess
            return;
        }
        if (tags.listener() != null) {
            moddingfromamod$poison(e);   // already reporting to another lookup's index
            return;
        }
        moddingfromamod$index.added(e, tags);
        TagIndex<Entity> ix = moddingfromamod$index;
        tags.listen(new ObservedTagSet.Listener() {
            @Override
            public void tagAdded(String tag) {
                ix.tagAdded(e, tag);
            }

            @Override
            public void tagRemoved(String tag) {
                ix.tagRemoved(e, tag);
            }
        });
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void moddingfromamod$removed(T t, CallbackInfo ci) {
        if (!(t instanceof Entity e) || !moddingfromamod$index.contains(e) || byId.get(e.getId()) == t) {
            return;
        }
        moddingfromamod$index.removed(e);
        if (e.getTags() instanceof ObservedTagSet tags) {
            tags.listen(null);
        }
    }

    @Unique
    private void moddingfromamod$poison(Entity e) {
        if (!moddingfromamod$poisoned) {
            moddingfromamod$poisoned = true;
            SelectorIndex.poisoned(this, e);
        }
    }
}
