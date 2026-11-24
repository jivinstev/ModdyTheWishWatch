package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * <b>Put command text into THIS build's dialect</b>: every version-specific spelling a
 * command, a template or the agent can carry, corrected at the few places text becomes something
 * Minecraft parses.
 *
 * <p>The build has two targets (1.21.4 canonical, a 1.21.1 compat build), and the same sentence is
 * spelled differently on each in two places that matter:
 * <ul>
 *   <li><b>attribute ids</b> — {@code minecraft:generic.scale} on 1.21.1, {@code minecraft:scale}
 *       from 1.21.2. Decided by the LIVE REGISTRY in {@link AttributeIds}, in both directions.</li>
 *   <li><b>{@code custom_model_data}</b> — a bare int on 1.21.1, {@code {floats:[3f]}} on 1.21.4.
 *       Decided by the compat pair {@code compat.ModelData}.</li>
 *   <li><b>sound ids</b> — not a version difference but the same failure: a colon where a dot
 *       goes ({@code minecraft:entity:pig:ambient}) is refused at pack load. {@link SoundIds}.</li>
 *   <li><b>an {@code attribute} aimed at many entities</b> — also not a version difference: the
 *       command takes ONE, and {@code attribute @e[type=zombie] …} is refused at parse (at pack
 *       load, in a datapack). {@link AttributeSelectors}.</li>
 * </ul>
 *
 * <p>Both are refused outright by the version they are not written for, and one of them — an
 * attribute id inside a datapack function — is refused SILENTLY at pack load. So the correction
 * sits at the choke points rather than in guidance the agent (or a template author) would have to
 * remember: {@link WishExecutor} for every command, {@code DatapackForge.compile} for every
 * datapack, and {@code AuthorDatapackTool}, which repeats it so it can tell the agent. Anything
 * that CHECKS shipped text (a template, an example script) must judge it through here too, because
 * this is what the game is actually handed.
 */
public final class CommandDialect {

    private CommandDialect() {
    }

    /** {@link #correct} with the live attribute registry as the judge. */
    public static AttributeIds.Fixed correct(String text) {
        return correct(text, CommandDialect::attributeExists);
    }

    public static AttributeIds.Fixed correct(String text, Predicate<String> attributeExists) {
        AttributeIds.Fixed attrs = AttributeIds.correct(text, attributeExists);
        List<String> notes = new ArrayList<>(attrs.notes());
        String out = com.moddingfromamod.compat.ModelData.correctSpelling(attrs.command(), notes);
        // A carrier handed out names its own picture on 1.21.4 (and the 1.21.4 spelling is
        // dropped, loudly, on 1.21.1). After correctSpelling, which it reads the result of.
        out = com.moddingfromamod.compat.ModelData.nameTheModel(out, notes);
        // a sound id spelled with colons makes Minecraft refuse the whole function at pack load.
        out = SoundIds.correct(out, notes);
        // An `attribute` aimed at many entities (@e[type=zombie]) is refused by the parser — at pack
        // load inside a datapack, taking the whole function with it. execute as … run attribute @s.
        out = AttributeSelectors.correct(out, notes);
        out = EntityNbtKeys.correct(out, notes);   // 1.21.5: FallDistance is the double fall_distance
        // the 1.21.5 spellings of item components and text (dyed_color, enchantments,
        // attribute_modifiers, quoted JSON names/lore/sign text). Refused at load, or silently wrong.
        out = com.moddingfromamod.compat.ComponentSpelling.correct(out, notes);
        return new AttributeIds.Fixed(out, notes);
    }

    public static boolean attributeExists(String id) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.containsKey(
                    net.minecraft.resources.ResourceLocation.parse(id));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
