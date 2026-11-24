package com.moddingfromamod.wish;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Entity NBT keys that 26.1 renamed, spelled the 26.1 way.</b> Minecraft 26.1 saves an entity's fall distance as
 * {@code fall_distance} ({@code Entity.TAG_FALL_DISTANCE}), not {@code FallDistance}: a summon or {@code data} line in
 * the old spelling still parses and silently sets nothing, so a "no fall damage" or "fall slowly" wish does nothing.
 * The moddy-cbscript compiler writes the new key from 26.1 ({@code mc/26.1.2}); this catches the words the agent or a
 * player types into a command. Pure (no Minecraft), so {@code EntityNbtKeysTest} holds it in Gate A.
 */
public final class EntityNbtKeys {

    private EntityNbtKeys() {
    }

    /** Old key → 26.1 key. Only keys a wish has a reason to write. */
    static final Map<String, String> RENAMED = Map.of("FallDistance", "fall_distance");

    /** A key in a compound ({@code {FallDistance:0f}}) or a data path ({@code data get entity @s FallDistance}). */
    private static final Pattern KEY = Pattern.compile("(?<![\\w\"])(FallDistance)(?![\\w\"])");

    public static String correct(String text, List<String> notes) {
        if (text == null || !text.contains("FallDistance")) {
            return text;
        }
        Matcher m = KEY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String now = RENAMED.get(m.group(1));
            String note = "the entity NBT key '" + m.group(1) + "' to '" + now + "'";
            if (!notes.contains(note)) {
                notes.add(note);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(now));
        }
        m.appendTail(out);
        return out.toString();
    }
}
