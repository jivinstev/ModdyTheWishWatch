package com.moddingfromamod.compat;

/**
 * <b>Item components as a command spells them on 1.21.8</b>. 1.21.5 changed three spellings
 * every give line the mod writes uses, and each old form is refused or, worse, accepted as something else:
 * <ul>
 *   <li>{@code dyed_color={rgb:N}} → {@code dyed_color=N} (the old form: "Malformed 'minecraft:dyed_color'",
 *       measured on the first 1.21.8 Gate B — one refused line switches off the whole datapack);</li>
 *   <li>{@code attribute_modifiers={modifiers:[…],show_in_tooltip:false}} → a bare list, the hiding moved to
 *       {@code tooltip_display};</li>
 *   <li>text in a component is SNBT, not JSON in a string: {@code custom_name='{"text":"X"}'} would NAME the
 *       item {@code {"text":"X"}}. A JSON object is valid SNBT, so the same text goes in unquoted.</li>
 * </ul>
 * The compiler corrects the old forms in what the AGENT writes (cbscript component_syntax.py); this is what
 * the mod writes itself, so it is right at the source.
 */
public final class ItemSyntax {

    private ItemSyntax() {
    }

    public static String dyedColor(int rgb) {
        return "minecraft:dyed_color=" + rgb;
    }

    /** The modifiers component from its entries ({@code {type:…,id:…,amount:…,operation:…,slot:…}}, comma-joined). */
    public static String attributeModifiers(String entries) {
        return "minecraft:attribute_modifiers=[" + entries + "]";
    }

    /** Hides these components' tooltip lines (each a full id, e.g. {@code minecraft:attribute_modifiers}). */
    public static String hideLines(String... components) {
        StringBuilder b = new StringBuilder("minecraft:tooltip_display={hidden_components:[");
        for (int i = 0; i < components.length; i++) {
            b.append(i == 0 ? "" : ",").append('"').append(components[i]).append('"');
        }
        return b.append("]}").toString();
    }

    /** A text component (JSON text, which is valid SNBT) as a component value: no quotes around it. */
    public static String text(String jsonText) {
        return jsonText;
    }
}
