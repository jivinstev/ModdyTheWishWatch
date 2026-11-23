package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Correct the attribute ids this build renamed, the way {@code nbt_case} corrects NBT capitals.</b>
 *
 * <p>On 1.21.1 the attribute is {@code minecraft:generic.scale}. Later versions dropped the
 * {@code generic.}, and the shorter name is what a model writes — so the agent produces
 * {@code attribute … minecraft:scale}, Minecraft answers <i>"Can't find element 'minecraft:scale'"</i>,
 * and the wish is a round trip poorer. The same holds for {@code movement_speed} and
 * {@code attack_damage}.
 *
 * <p><b>Measured, and it is not occasional.</b> Sampling this machine's whole action log turns up
 * the failure again and again across weeks of real wishes:
 *
 * <pre>
 * ERR  attribute Dev minecraft:scale base set 5        -> Can't find element 'minecraft:scale'
 * OK   attribute Dev generic.scale base set 5
 * ERR  attribute @e[tag=wishmob_snorble,…] minecraft:scale base set 1.45
 * ERR  attribute @s minecraft:scale base set 1.0
 * OK   attribute Dev minecraft:generic.scale base set 1.8
 * </pre>
 *
 * <p><b>Why a transform and not another lesson.</b> The lesson already exists — the agent has WRITTEN
 * IT DOWN in its own handover notes ("<i>minecraft:movement_speed is REJECTED on this server; the
 * working id is minecraft:generic.movement_speed</i>") and then emitted the broken form again in a
 * later wish. That is precisely the argument {@code nbt_case.py} records for fixing wrong-cased NBT
 * in the compiler rather than in the guidance: written down, read, and got wrong anyway.
 *
 * <p><b>Why correcting is safe here.</b> The mapping is decided by the LIVE REGISTRY, not by a guess:
 * a name is rewritten only when {@code minecraft:<name>} is absent AND {@code minecraft:generic.<name>}
 * exists. So it can only ever turn a command that was certain to fail into the one the author meant,
 * and on a version where the short name is real it does nothing at all.
 *
 * <h2>⚠ AN ATTRIBUTE ID IS NAMED IN TWO SYNTAXES, AND THIS ONLY COVERED ONE</h2>
 *
 * <p>Everything above was true, wired to the right choke point ({@code WishExecutor}), and derived
 * from the registry — and it still let the failure through, because it matched the {@code attribute
 * <target> <id>} <b>command</b> and nothing else. The other place a command names an attribute is
 * the {@code minecraft:attribute_modifiers} <b>item component</b>, which is how an agent gives a
 * child a helmet that makes them jump:
 *
 * <pre>
 * give @p minecraft:diamond_helmet[minecraft:attribute_modifiers=[
 *     {type:"minecraft:jump_strength", id:"wish:super_jump", amount:1.2, …}]]
 *   Malformed 'minecraft:attribute_modifiers' component: 'Unknown registry key in
 *   ResourceKey[minecraft:root / minecraft:attribute]: minecraft:jump_strength; … minecraft:armor;
 *   … minecraft:safe_fall_distance'
 * </pre>
 *
 * <p>Measured in {@code corpus/real-tool-calls.jsonl}: 4 of the agent's 12 real {@code run_command}
 * failures are this, across two wishes — and in both the agent then <b>corrected itself to
 * {@code generic.}</b> on the very next call, so the whole cost was a round trip spent
 * rediscovering something this class already knew. Three other places in this repo carry the same
 * fact in a comment ({@code ItemProperties}, {@code ModifyMobTool}, {@code AuthorDatapackTool}); the
 * one path that hands the child the item had none of it.
 *
 * <p>So {@link #correct} now also rewrites the {@code type:} of each modifier — and <b>only</b>
 * inside an {@code attribute_modifiers} value, found by balanced-bracket scan rather than by
 * matching {@code type:} anywhere. The registry predicate would very likely have made a looser
 * match harmless, but "very likely harmless" is the reasoning this repo keeps unpicking, and a
 * {@code type:} in some unrelated NBT is not this class's business.
 *
 * <h2>⚠ THERE ARE THREE SYNTAXES, AND THE THIRD IS THE ONLY SILENT ONE</h2>
 *
 * <p>The heading above says "two", and that was wrong. The third is the {@code attributes:[…]} NBT
 * list on {@code /summon}, which is how a template makes a mob bigger and tougher:
 *
 * <pre>
 * summon hybrid_aquatic:great_white_shark ~ ~ ~ {attributes:[{id:"minecraft:scale",base:4.0},
 *                                                            {id:"minecraft:max_health",base:300}]}
 * </pre>
 *
 * <p><b>This one does not fail.</b> The other two return an error the agent sees and corrects itself
 * from on the next call — which is why they cost a round trip and nothing worse. The summon
 * SUCCEEDS, logs {@code Ignoring unknown attribute 'minecraft:scale'} at WARN, and spawns the mob
 * with none of the attributes applied. So it reaches the child.
 *
 * <p>Measured in Steve's own world, 10 Sep 2026, and the shape is the worst available: the FIRST
 * wish goes through the agent and works, the template it teaches itself carries the short ids, and
 * every instant re-wish afterwards is a silent downgrade. He asked again for his Great Big Shark —
 * 4x size, 300 health, big chomp — the game announced "Your Great Big Shark is here!", and an
 * ordinary great white arrived. Nothing in the game said the wish had half-failed.
 *
 * <p>Correcting at {@code WishExecutor} rather than only when a template is taught is deliberate:
 * templates carrying the short ids are already saved in his world, and this repairs them in place.
 */
public final class AttributeIds {

    /** {@code attribute <target> <id> …} — the id is the token after the target. */
    private static final Pattern ATTRIBUTE_COMMAND =
            Pattern.compile("(\\battribute\\s+\\S+\\s+)([a-z0-9_.:]+)", Pattern.CASE_INSENSITIVE);

    /** Where an {@code attribute_modifiers} component value starts. */
    private static final Pattern MODIFIERS_COMPONENT =
            Pattern.compile("attribute_modifiers\\s*=", Pattern.CASE_INSENSITIVE);

    /** One modifier's {@code type:"<id>"} — quoted or bare, spacing free. */
    private static final Pattern MODIFIER_TYPE =
            Pattern.compile("(\\btype\\s*:\\s*\"?)([a-z0-9_.:]+)(\"?)", Pattern.CASE_INSENSITIVE);

    /** Where an entity's {@code attributes:[…]} NBT list starts, on /summon and /data.
     *  {@code \\b} keeps this off {@code attribute_modifiers=}, which the pass above owns. */
    private static final Pattern ATTRIBUTES_NBT =
            Pattern.compile("\\battributes\\s*:\\s*", Pattern.CASE_INSENSITIVE);

    /** One entry's {@code id:"<id>"} inside that list. */
    private static final Pattern NBT_ID =
            Pattern.compile("(\\bid\\s*:\\s*\"?)([a-z0-9_.:]+)(\"?)", Pattern.CASE_INSENSITIVE);

    private AttributeIds() {
    }

    /** Names corrected during this run, for the caller to mention. */
    public record Fixed(String command, List<String> notes) {
    }

    /**
     * Rewrite any attribute id the live registry cannot resolve but can resolve with the other
     * target's spelling -- a {@code generic.}/{@code player.}/{@code zombie.} prefix added (1.21.1) or
     * removed (1.21.2+).
     *
     * @param exists answers whether a fully-qualified id is in the registry — injected so this class
     *               stays testable without a game
     */
    public static Fixed correct(String command, java.util.function.Predicate<String> exists) {
        if (command == null || !command.toLowerCase(Locale.ROOT).contains("attribute")) {
            return new Fixed(command, List.of());
        }
        List<String> notes = new ArrayList<>();
        Matcher m = ATTRIBUTE_COMMAND.matcher(command);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = corrected(m.group(2), exists, notes);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + replacement));
        }
        m.appendTail(out);
        String fixed = correctScoped(out.toString(), MODIFIERS_COMPONENT, MODIFIER_TYPE, exists, notes);
        fixed = correctScoped(fixed, ATTRIBUTES_NBT, NBT_ID, exists, notes);
        return new Fixed(fixed, notes);
    }

    /**
     * The 1.21.1 spelling of {@code id}, or {@code id} itself when nothing needs doing.
     *
     * <p>Only ever rewrites a name the registry cannot resolve into one it can, so the worst case
     * is leaving a wrong id exactly as the author wrote it for Minecraft to complain about — never
     * swapping one failure for a different one.
     */
    private static String corrected(String id, java.util.function.Predicate<String> exists,
                                    List<String> notes) {
        String qualified = id.contains(":") ? id : "minecraft:" + id;
        if (exists.test(qualified)) {
            return id;
        }
        // BOTH DIRECTIONS, because this build now has two targets. 1.21.1 prefixes every
        // attribute (generic./player./zombie.) and 1.21.2+ dropped all three, so a name spelled for
        // the other version is corrected into this one's. Still decided by the live registry and
        // nothing else: exactly one candidate can exist, because on a given version the prefixed
        // and the bare forms never both do.
        String ns = qualified.substring(0, qualified.indexOf(':') + 1);
        String path = qualified.substring(ns.length());
        List<String> candidates = new ArrayList<>();
        java.util.regex.Matcher prefixed = PREFIXED.matcher(path);
        if (prefixed.matches()) {
            candidates.add(ns + prefixed.group(2));
        } else {
            for (String p : PREFIXES) {
                candidates.add(ns + p + "." + path);
            }
        }
        for (String candidate : candidates) {
            if (exists.test(candidate)) {
                // Keep the author's own shape: a bare `scale` stays bare, and a namespaced
                // `minecraft:scale` keeps its namespace.
                String replacement = id.contains(":") ? candidate : candidate.substring(ns.length());
                notes.add(id + " -> " + replacement);
                return replacement;
            }
        }
        return id;
    }

    /** The three prefixes 1.21.1 carries and 1.21.2 removed. */
    private static final String[] PREFIXES = {"generic", "player", "zombie"};
    private static final Pattern PREFIXED = Pattern.compile("(generic|player|zombie)\\.(.+)");

    /**
     * Correct each modifier's {@code type:} inside every {@code attribute_modifiers} component.
     *
     * <p><b>Scoped by balanced-bracket scan, on purpose.</b> A bare {@code type:} match over the
     * whole command would almost certainly be harmless — the registry predicate rejects anything
     * that is not a real attribute under a {@code generic.} prefix — but a {@code type:} inside some
     * unrelated NBT is not this class's to touch, and "almost certainly harmless" is exactly the
     * reasoning this repo keeps having to unpick.
     */
    private static String correctScoped(String command, Pattern opener, Pattern key,
                                        java.util.function.Predicate<String> exists,
                                        List<String> notes) {
        Matcher c = opener.matcher(command);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (c.find(copied)) {
            int start = c.end();
            int end = endOfValue(command, start);
            if (end < 0) {
                break;          // unbalanced — leave the rest exactly as written
            }
            out.append(command, copied, start);
            Matcher t = key.matcher(command.substring(start, end));
            StringBuilder span = new StringBuilder();
            while (t.find()) {
                t.appendReplacement(span, Matcher.quoteReplacement(
                        t.group(1) + corrected(t.group(2), exists, notes) + t.group(3)));
            }
            t.appendTail(span);
            out.append(span);
            copied = end;
        }
        out.append(command.substring(copied));
        return out.toString();
    }

    /**
     * Index just past the bracketed value starting at {@code from}, or -1 if it never closes.
     *
     * <p>Quote-aware, because a modifier's {@code id} and {@code slot} are quoted strings and a
     * custom name three components earlier can contain any bracket at all.
     */
    private static int endOfValue(String s, int from) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        // Skip any space between the `=` and the value. Without this a single stray space makes
        // the "an unbracketed value ended" branch below fire on character one, so the whole
        // component is silently skipped -- a false negative that looks exactly like the bug this
        // method exists to fix.
        while (from < s.length() && Character.isWhitespace(s.charAt(from))) {
            from++;
        }
        for (int i = from; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quote) {
                    inString = false;
                }
                continue;
            }
            switch (ch) {
                case '"', '\'' -> {
                    inString = true;
                    quote = ch;
                }
                case '[', '{' -> depth++;
                case ']', '}' -> {
                    depth--;
                    if (depth <= 0) {
                        // depth 0 closes the value; below 0 means we ran out of the enclosing
                        // component list, which is the malformed case and stops the scan.
                        return depth == 0 ? i + 1 : -1;
                    }
                }
                default -> {
                    if (depth == 0 && (ch == ',' || ch == ' ')) {
                        return i;   // an unbracketed value ended
                    }
                }
            }
        }
        return depth == 0 ? s.length() : -1;
    }
}
