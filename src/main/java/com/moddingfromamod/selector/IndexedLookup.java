package com.moddingfromamod.selector;

import net.minecraft.world.entity.Entity;

/** What the selector hook asks of an entity lookup ({@code mixin/EntityLookupIndexMixin}). */
public interface IndexedLookup {

    TagIndex<Entity> moddingfromamod$tagIndex();

    /** True once an entity turned up that this lookup cannot index: vanilla answers there from then on. */
    boolean moddingfromamod$poisoned();
}
