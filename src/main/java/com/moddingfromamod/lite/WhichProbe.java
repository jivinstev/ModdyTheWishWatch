package com.moddingfromamod.lite;

import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.wish.WhichMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The stock-client half of {@code ask_which}, staged for {@code tools/stock-which-gate.sh}.</b>
 *
 * <p>Two things only, and neither decides anything the gate measures:
 * <ol>
 *   <li>at the stock child's join, he owns two castles — memory-only
 *       ({@link BuildRegistry#putInMemoryForProbe}, never a shelf, docs/WISH_STORES.md §9), anchored
 *       far behind him so neither is a fact "in his view" that would let the agent skip the
 *       question;</li>
 *   <li>a marker file the moment his open menu is a {@link WhichMenu}, and another when it closes,
 *       so the harness clicks a chest that is really there rather than a timed guess.</li>
 * </ol>
 * The wish itself is TYPED by the stock client ({@code /wish …}), granted by the real agent, and the
 * verdict is read from the agent's own tool calls — this class neither asks nor answers.
 *
 * <p>Reached through {@link LiteProbe} (registered only when {@code liteprobe} names a directory),
 * and inert unless {@code -Dmoddingfromamod.whichprobe=<dir>} is set as well.
 */
public final class WhichProbe {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-which-probe");

    public static final String PROPERTY = ModdingFromAModMod.MODID + ".whichprobe";

    /** The two things he owns, with one word in common — the shape `ask_which` exists for. */
    public static final String[][] CASTLES = {{"red_castle", "red castle"}, {"blue_castle", "blue castle"}};

    private static boolean wasOpen;

    private WhichProbe() {
    }

    public static boolean active() {
        return !System.getProperty(PROPERTY, "").isBlank();
    }

    /** Join: he owns two castles, far behind him. Server thread. */
    static void seed(ServerPlayer player) {
        if (!active()) {
            return;
        }
        int i = 0;
        for (String[] c : CASTLES) {
            BuildPlan plan = BuildPlan.fromJson(com.google.gson.JsonParser.parseString(
                    "{\"id\":\"" + c[0] + "\",\"name\":\"" + c[1] + "\",\"shapes\":[{\"kind\":\"box\","
                            + "\"width\":3,\"height\":3,\"depth\":3,\"block\":\"minecraft:stone_bricks\"}]}")
                    .getAsJsonObject());
            BuildRegistry.putInMemoryForProbe(plan);
            BuildRegistry.rememberAnchor(c[0], new BlockPos((int) player.getX() - 300 - 40 * i,
                    (int) player.getY(), (int) player.getZ() - 300).asLong(), player.level().dimension());
            i++;
        }
        mark("seeded", CASTLES[0][0] + " " + CASTLES[1][0]);
    }

    /** Every tick: is the chest open on him? Server thread. */
    static void tick(ServerPlayer player) {
        if (!active() || player == null) {
            return;
        }
        boolean open = player.containerMenu instanceof WhichMenu;
        if (open && !wasOpen) {
            mark("which-open", "menu=" + player.containerMenu.getClass().getSimpleName()
                    + " type=" + net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(
                            player.containerMenu.getType()));
        } else if (!open && wasOpen) {
            mark("which-closed", "closed");
        }
        wasOpen = open;
    }

    private static void mark(String name, String body) {
        try {
            Path d = Path.of(System.getProperty(PROPERTY).trim());
            Files.createDirectories(d);
            Files.writeString(d.resolve(name), body + "\n");
            LOG.info("WHICH {}: {}", name, body);
        } catch (Exception e) {
            LOG.error("could not write the {} marker", name, e);
        }
    }
}
