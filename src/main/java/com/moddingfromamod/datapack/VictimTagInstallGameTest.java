package com.moddingfromamod.datapack;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * <b> where it RUNS: the starter cookie, installed the way a world installs it, still shrinks
 * and still wears off — and an entity left mid-timer by the OLD pack wears off too.</b>
 *
 * <p>The jar's {@code tiny_cookie_grow.zip} was compiled from the pre-#1871 grow template; every
 * install rewrites it ({@link VictimTagFix}, through {@link DatapackStore#installBytes}). This puts
 * it through that install, then: a pig shrunk by the pack's own {@code effect} must come back to
 * size and lose its tag; and a pig carrying only the old pack's leftovers (a score, no tag — a child
 * who logged off mid-shrink when the server upgraded) must be adopted by the sweep and come back too,
 * rather than staying tiny for ever. Own batch: the pack and its objective are shared.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VictimTagInstallGameTest {

    private static final String PACK = "tiny_cookie_grow";

    @GameTest(template = "empty_test", batch = "victim_tag_install", timeoutTicks = 1200)
    public static void theInstalledCookieShrinksAndWearsOffAndAdoptsAnOldTimer(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        // what a world is given carries the fix
        String tick = function(DatapackStore.installBytes(PACK), "tick");
        helper.assertTrue(tick.contains("tag=mfam_grow_on"), Component.literal(
                "the installed cookie's tick does not tag its victims -- the install rewrite did not run: " + tick));
        DatapackReloader.installAndReload(server, PACK, DatapackStore.zipFor(PACK));

        String p = "gtvt" + Long.toHexString(System.nanoTime() & 0xffffff);
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3)));
        CommandSourceStack src = server.createCommandSourceStack().withLevel(helper.getLevel())
                .withPosition(at).withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput();
        Runnable[] step = new Runnable[1];
        int[] waited = {0};
        boolean[] started = {false};
        step[0] = () -> {
            waited[0] += 5;
            if (!started[0]) {
                if (DatapackReloader.reloadInProgress() || DatapackReloader.reloadDeferred()
                        || server.getFunctions().get(Identifier.fromNamespaceAndPath(PACK, "effect")).isEmpty()) {
                    if (waited[0] > 600) {
                        helper.fail(Component.literal("the cookie's pack never loaded"));
                        return;
                    }
                    helper.runAfterDelay(5, () -> step[0].run());   // a FRESH Runnable each time (SourceRuleSweepTest)
                    return;
                }
                started[0] = true;
                cmd(server, src, "summon pig ~ ~ ~ {NoAI:1b,Tags:[\"" + p + "_fresh\"]}");
                cmd(server, src, "summon pig ~2 ~ ~ {NoAI:1b,Tags:[\"" + p + "_old\"]}");
                cmd(server, src, "execute as @e[tag=" + p + "_fresh] at @s run function " + PACK + ":effect");
                Entity fresh = one(helper, p + "_fresh");
                helper.assertTrue(fresh != null && scale(fresh) < 0.5 && fresh.entityTags().contains("mfam_grow_on"),
                        Component.literal("the cookie did not shrink and tag the pig: " + (fresh == null ? "no pig"
                                : scale(fresh) + " " + fresh.entityTags())));
                cmd(server, src, "scoreboard players set @e[tag=" + p + "_fresh] mfam_grow 3");
                // the OLD pack's leftovers: shrunk, a score, and no tag
                cmd(server, src, "attribute @e[tag=" + p + "_old,limit=1] minecraft:scale base set 0.3");
                cmd(server, src, "scoreboard players set @e[tag=" + p + "_old] mfam_grow 3");
                waited[0] = 0;
                helper.runAfterDelay(5, () -> step[0].run());   // a FRESH Runnable each time (SourceRuleSweepTest)
                return;
            }
            Entity fresh = one(helper, p + "_fresh");
            Entity old = one(helper, p + "_old");
            boolean freshDone = fresh != null && scale(fresh) == 1.0 && !fresh.entityTags().contains("mfam_grow_on");
            boolean oldDone = old != null && scale(old) == 1.0;
            if (freshDone && oldDone) {
                helper.succeed();
            } else if (waited[0] > 400) {   // the sweep runs every 200 ticks
                helper.fail(Component.literal("after " + waited[0] + " ticks: fresh pig "
                        + (fresh == null ? "gone" : scale(fresh) + " " + fresh.entityTags()) + ", old-timer pig "
                        + (old == null ? "gone" : scale(old) + " " + old.entityTags())
                        + " -- a shrink that never wears off"));
            } else {
                helper.runAfterDelay(5, () -> step[0].run());   // a FRESH Runnable each time (SourceRuleSweepTest)
            }
        };
        helper.runAfterDelay(1, () -> step[0].run());
    }

    private static void cmd(MinecraftServer s, CommandSourceStack src, String c) {
        s.getCommands().performPrefixedCommand(src, c);
    }

    private static Entity one(GameTestHelper helper, String tag) {
        for (Entity e : helper.getLevel().getEntities().getAll()) {
            if (e.entityTags().contains(tag) && e.isAlive()) {
                return e;
            }
        }
        return null;
    }

    private static double scale(Entity e) {
        var a = e instanceof LivingEntity l ? l.getAttribute(Attributes.SCALE) : null;
        return a == null ? -1 : a.getBaseValue();
    }

    private static String function(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().endsWith("/function/" + name + ".mcfunction")) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
