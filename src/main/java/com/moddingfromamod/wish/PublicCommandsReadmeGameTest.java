package com.moddingfromamod.wish;

import com.moddingfromamod.testing.CommandTree;
import com.moddingfromamod.testing.CommandsReadme;
import com.moddingfromamod.testing.GateShards;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * <b>The public README lists every command the public build registers, and nothing else</b>. The tree is the mod's
 * own: every {@code RegisterCommandsEvent} handler in its classes, fired into a fresh dispatcher with the live server's
 * build context ({@link CommandTree}); and every path in it must also be in the LIVE server's dispatcher, so the tree
 * read is the one a player gets. A command added without its README row fails here by name, and so does a row for a
 * command this build does not have (a private-only one above all).
 *
 * <p><b>Why this is a GameTest and not a unit test (from Minecraft 1.21.11 on).</b> It was {@code
 * PublicCommandsReadmeTest} in Gate A through 1.21.10. In 1.21.11 {@code net.minecraft.commands.Commands} holds static
 * {@code PermissionCheck} fields ({@code LEVEL_GAMEMASTERS}, ...) whose class initialisation reaches
 * {@code Permission}, which reads {@code BuiltInRegistries}, which refuses to load before {@code Bootstrap}; and
 * {@code Bootstrap} cannot run in a plain JUnit JVM (no FML loader: {@code SharedConstants} asks FMLEnvironment).
 * Even {@code Commands.literal("x")} throws there. Do not move it back to Gate A: the coverage moved, it was not
 * dropped.
 *
 * <p>Reads the public README where it is in each tree: {@code docs/public-repo/root/README.md} here,
 * {@code README.md} in the public repo (where {@code docs/public-repo/root/} is the root).
 */
@GameTestHolder(GateShards.CI)
public class PublicCommandsReadmeGameTest {

    static Path readme() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            Path here = p.resolve("docs/public-repo/root/README.md");
            if (Files.exists(here)) {
                return here;
            }
            if (Files.exists(p.resolve("README.md")) && Files.isDirectory(p.resolve("src/main/java"))) {
                return p.resolve("README.md");
            }
            p = p.getParent();
        }
        throw new IllegalStateException("no README above " + System.getProperty("user.dir"));
    }

    @GameTest(template = "empty_test")
    public static void theReadmeListsExactlyThePublicCommandTree(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TreeSet<String> real;
        String text;
        try {
            real = CommandTree.paths(CommandTree.build(
                    CommandBuildContext.simple(server.registryAccess(), server.getWorldData().enabledFeatures())));
            text = Files.readString(readme());
        } catch (Exception e) {
            helper.fail(Component.literal("could not read the command tree or the README: " + e));
            return;
        }
        check(helper, real.contains("moddy magician") && real.contains("wish"),
                "the tree came back empty: " + real + " (" + CommandTree.describeSearch() + ")");
        check(helper, !real.contains("moddyop"), "a private command is in the public build's tree");
        TreeSet<String> live = CommandTree.paths(server.getCommands().getDispatcher());
        TreeSet<String> notLive = new TreeSet<>(real);
        notLive.removeAll(live);
        check(helper, notLive.isEmpty(), "registered by the mod's handlers but not in the live dispatcher: " + notLive);
        TreeSet<String> doc = CommandsReadme.documented(text, "Commands");
        TreeSet<String> missing = new TreeSet<>(real);
        missing.removeAll(doc);
        TreeSet<String> extra = new TreeSet<>(doc);
        extra.removeAll(real);
        check(helper, missing.isEmpty(), "registered by the public build, missing from the README's Commands: " + missing);
        check(helper, extra.isEmpty(), "named in the README's Commands, not registered by the public build: " + extra);
        check(helper, !text.contains("moddyop"), "the public README names a private command");
        helper.succeed();
    }

    private static void check(GameTestHelper helper, boolean ok, String why) {
        if (!ok) {
            helper.fail(Component.literal(why));
        }
    }
}
