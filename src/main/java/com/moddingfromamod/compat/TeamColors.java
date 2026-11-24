package com.moddingfromamod.compat;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.world.scores.TeamColor;

/**
 * 26.2 gave a team its own colour enum, {@link TeamColor}, in place of {@code ChatFormatting}: {@code setColor} and
 * {@code getColor} take and return an {@code Optional<TeamColor>}, and {@code ChatFormatting.getColor()} is gone.
 */
public final class TeamColors {
    private TeamColors() {
    }

    /**
     * What {@code ChatFormatting.getByName(name)} found for a colour on 26.1.2: the name compared in lower case with
     * every character that is not a letter dropped ({@code "Light Purple"} is {@code light_purple}). A name that is no
     * colour (a style such as {@code bold}, or nothing) is empty.
     */
    public static Optional<TeamColor> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String clean = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (TeamColor c : TeamColor.values()) {
            if (c.getSerializedName().replace("_", "").equals(clean)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
