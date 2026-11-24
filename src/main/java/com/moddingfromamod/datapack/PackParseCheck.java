package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Would the server refuse a function in this pack? Asked BEFORE the pack is switched on.</b>
 *
 * <p>Minecraft parses every function at reload and drops one it cannot parse, logging
 * {@code Failed to load function …} with a {@code Caused by: … Whilst parsing command on line N}
 * stack trace. Our tools used to let that happen and then take the pack back off
 * ({@link DatapackReloader#takeBackIfRefused}), so every refused COMMAND put a stack trace in the
 * server log — on a house, and in every Gate B run whose tests prove the refusal
 * ({@code ApplyEffectGameTest.aCommandTheServerRefusesIsReportedNotLive}'s {@code damage @s twelve}).
 * A gate that reads the log as a crash (the public repo's port CI, MMVU's {@code ci-gates.py})
 * failed on it. This parses the pack's functions the way {@code ServerFunctionLibrary.reload} does,
 * with the same dispatcher and the same source, so a refused pack is never selected at all.
 *
 * <p>One thing the reload can see that this cannot: the pack's OWN reloadable data. A command that
 * names a tag, loot table, predicate or item modifier is resolved at PARSE time
 * ({@code ResourceOrIdArgument.ReferenceResult}, block/item tag predicates), and the reload parses
 * against the registries the pack itself is adding; this parses against the ones loaded now. So in
 * a pack that ships such data, a "can't find it" failure is not counted — the reload may well find
 * it — and the old after-load check still catches it if not ({@link #countsAsRefusal}).
 */
public final class PackParseCheck {
    private PackParseCheck() {
    }

    /** {@code data/<ns>/function/<path>.mcfunction} — the only folder 1.21 loads functions from. */
    private static final Pattern FUNCTION = Pattern.compile("^data/([^/]+)/function/(.+)\\.mcfunction$");

    /** Pack data a command can name and that is resolved while the function is parsed. */
    private static final Pattern PARSE_TIME_DATA = Pattern.compile(
            "^data/[^/]+/(loot_table|predicate|item_modifier)/.+|^data/[^/]+/tags/(?!function/).+");

    /** The en_us words of the "names something not loaded (yet)" parse errors. */
    private static final Pattern MISSING_REFERENCE = Pattern.compile(
            "(?i)can't find (element|tag)|unknown (block |item |entity )?tag|unknown (predicate|loot table|item modifier)");

    /** Pure: {@code ns:path} for a zip entry that is a function the server loads, else null. */
    static String functionId(String entryName) {
        Matcher m = FUNCTION.matcher(entryName);
        return m.matches() ? m.group(1) + ":" + m.group(2) : null;
    }

    /** Pure: does this zip entry carry data a command is resolved against while it parses? */
    static boolean isParseTimeData(String entryName) {
        return PARSE_TIME_DATA.matcher(entryName).matches();
    }

    /**
     * Pure: is a parse failure one the reload would refuse too? Always, unless the pack ships
     * parse-time data and the failure only says something it names is not loaded — which the
     * reload, loading that data, may find.
     */
    static boolean countsAsRefusal(String failure, boolean packShipsParseTimeData) {
        return !packShipsParseTimeData || !MISSING_REFERENCE.matcher(failure == null ? "" : failure).find();
    }

    /**
     * Every function in {@code zip} the server would refuse, as {@code "ns:path — <Minecraft's
     * reason>"} (the form {@link DatapackVerifier.Report#missing()} uses). Empty when every function
     * parses, when the zip is absent, or when it cannot be read — a pack we cannot read is left to
     * the after-load check rather than refused on our say-so. Server thread.
     */
    public static List<String> refused(MinecraftServer server, Path zip) {
        List<String> out = new ArrayList<>();
        if (server == null || zip == null || !Files.isRegularFile(zip)) {
            return out;
        }
        Map<String, List<String>> functions = new TreeMap<>();
        boolean parseTimeData = false;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                var e = it.nextElement();
                String name = e.getName();
                parseTimeData |= isParseTimeData(name);
                String id = functionId(name);
                if (id != null) {
                    String text = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                    functions.put(id, List.of(text.split("\r?\n", -1)));
                }
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        // The source ServerFunctionLibrary.reload parses with: nobody, at the function level.
        CommandSourceStack src = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                server.getFunctionCompilationPermissions(), "", CommonComponents.EMPTY, null, null);
        var dispatcher = server.getCommands().getDispatcher();
        for (var f : functions.entrySet()) {
            Identifier key = Identifier.tryParse(f.getKey());
            if (key == null) {
                continue;   // an id the server cannot name it by; the after-load check reports it
            }
            try {
                CommandFunction.fromLines(key, dispatcher, src, f.getValue());
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (countsAsRefusal(msg, parseTimeData)) {
                    out.add(f.getKey() + " — " + (msg.length() > 240 ? msg.substring(0, 240) + "…" : msg));
                }
            }
        }
        return out;
    }
}
