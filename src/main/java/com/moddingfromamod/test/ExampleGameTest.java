package com.moddingfromamod.test;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B — headless runtime tests.</b> Run with {@code ./gradlew runGameTestServer}: boots a real
 * <i>dedicated server</i> with the mod loaded, runs every {@code @GameTest}, and exits non-zero on
 * any failure.
 *
 * <p><b>Why this gate exists:</b> a clean compile lies. Gate B is the only thing that catches
 * load-time and server-side failures — a frozen-registry violation, an unbound {@code Holder}, a
 * missing entity attribute, config read at registration time, a handler registered on the wrong bus,
 * or an NBT round-trip that throws on save. If the mod can't boot, this fails before any assertion.
 *
 * <p><b>What it CANNOT see:</b> anything client-side — rendering, model bake, sounds, GUIs, creative
 * tab building — and anything that only happens in singleplayer (client + integrated server in one
 * process). That's Gate C's job; see {@code docs/TESTING.md}.
 *
 * <p>Tests run inside the {@code empty_test} structure (a small floored box shipped at
 * {@code data/<modid>/structure/empty_test.nbt}). NeoForge 1.21.1 has no {@code @EmptyTemplate}, so a
 * real .nbt must exist on disk or the framework reports "Missing test structure" — regenerate with
 * {@code python3 tools/gen-empty-structure.py <modid> empty_test}.
 *
 * <p>Use {@link GameTestHelper#absolutePos} to convert structure-relative coords to world coords, and
 * always finish with {@link GameTestHelper#succeed()} (or a {@code helper.assert*} failure).
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ExampleGameTest {

    /**
     * The mod booted and its content actually landed in the live registries. Reaching this method at
     * all means the dedicated server started with the mod loaded — the single most valuable signal
     * here — and the assertions confirm registration really happened (not just that classes loaded).
     */
    @GameTest(template = "empty_test")
    public static void modLoadsAndRegistersContent(GameTestHelper helper) {
        Identifier gemId =
                Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "example_gem");
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(gemId),
                Component.literal("example_gem should be registered in the ITEM registry"));
        helper.assertTrue(ModItems.EXAMPLE_GEM.get() == BuiltInRegistries.ITEM.getValue(gemId),
                Component.literal("the DeferredRegister supplier should resolve to the registered item"));
        helper.succeed();
    }

    /**
     * A worked example of exercising the world: place a block, read it back, remove it. Swap this for
     * your own feature — spawn your mob and let it tick, fire your command, use your item, then assert
     * the world changed the way you expect.
     */
    @GameTest(template = "empty_test")
    public static void canModifyTheWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));

        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), Component.literal("stone should have been placed"));

        level.removeBlock(pos, false);
        helper.assertTrue(level.getBlockState(pos).isAir(), Component.literal("the block should be gone"));
        helper.succeed();
    }

    /**
     * <b>Pattern: stay green without optional mods.</b> When a test needs content from another mod,
     * check for it and {@code succeed()} early if it's absent, so a clean clone still passes. (Supply
     * the other mod at runtime with {@code -Psmokejars="/abs/other-mod.jar"} — see build.gradle.)
     */
    @GameTest(template = "empty_test")
    public static void skipsCleanlyWhenAnOptionalModIsMissing(GameTestHelper helper) {
        Identifier otherModMob = Identifier.parse("some_other_mod:some_mob");
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(otherModMob)) {
            // Not installed — nothing to test, and that's not a failure.
            helper.succeed();
            return;
        }
        // ...real assertions against that mod's content would go here...
        helper.succeed();
    }
}
