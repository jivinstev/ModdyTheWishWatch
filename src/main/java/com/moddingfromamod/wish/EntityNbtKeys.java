package com.moddingfromamod.wish;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>An entity's fall distance, spelled the way Minecraft has stored it since 1.21.5.</b> 1.21.5 renamed the entity
 * NBT key {@code FallDistance} to {@code fall_distance} and made it a double ({@code Entity} writes it with
 * {@code putDouble}). A command in the old spelling still parses and does nothing: {@code {FallDistance:0f}} names a
 * key no entity has, and even {@code {fall_distance:0f}} matches nothing in a selector's {@code nbt=} test, because NBT
 * matching compares tag types and a float is not a double. So a "no fall damage" or "float down gently" wish the agent
 * types does nothing at all.
 *
 * <p>Rewritten here, inside {@link CommandDialect}: the key in a compound or a data path becomes {@code fall_distance},
 * and a value written as a float or an integer becomes a double ({@code 0f} and {@code 0} become {@code 0d}). An
 * unsuffixed decimal ({@code 0.0}) is already a double and stays as written. The bundled compiler writes the same
 * spelling (moddy-cbscript #8). Pure, so {@code EntityNbtKeysTest} holds it in Gate A.
 */
public final class EntityNbtKeys {

    private EntityNbtKeys() {
    }

    private static final String OLD = "FallDistance";
    private static final String NEW = "fall_distance";

    /** A compound entry, {@code {FallDistance:0f}} or {@code ,fall_distance: 2}: the key, then a number. */
    private static final Pattern ENTRY = Pattern.compile(
            "([{,]\\s*)(" + OLD + "|" + NEW + ")(\\s*:\\s*)(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))([bBsSlLfFdD]?)(?![\\w.])");

    /** The old key anywhere else it stands alone: a data path ({@code data get entity @s FallDistance}). */
    private static final Pattern BARE = Pattern.compile("(?<![\\w\"])" + OLD + "(?![\\w\"])");

    public static String correct(String text, List<String> notes) {
        if (text == null || !(text.contains(OLD) || text.contains(NEW))) {
            return text;
        }
        Matcher m = ENTRY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(2);
            String number = m.group(4);
            String suffix = m.group(5);
            boolean isDouble = suffix.equalsIgnoreCase("d") || (suffix.isEmpty() && number.contains("."));
            String value = isDouble ? number + suffix : number + "d";
            if (key.equals(OLD)) {
                note(notes, "the entity NBT key '" + OLD + "' to '" + NEW + "'");
            }
            if (!isDouble) {
                note(notes, "the fall distance " + number + suffix + " to the double " + value);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + NEW + m.group(3) + value));
        }
        m.appendTail(out);
        String s = out.toString();
        Matcher bare = BARE.matcher(s);
        if (bare.find()) {
            note(notes, "the entity NBT key '" + OLD + "' to '" + NEW + "'");
            s = bare.replaceAll(NEW);
        }
        return s;
    }

    private static void note(List<String> notes, String note) {
        if (!notes.contains(note)) {
            notes.add(note);
        }
    }
}
