package com.moddingfromamod.config;

import java.util.List;

/**
 * One of the public mod's settings: its key, the words a player reads, its public default (null: the
 * reading code keeps its own), the values a player may pick, and whether a player may change it at all.
 *
 * @param playerChangeable false for a setting only an override or the server's file may set (safety, effort, the
 *                         pool): a player's request for it is refused by the action, whatever any menu shows
 * @param list             the value is a comma-separated, ordered list of {@code choices} (a fallback chain),
 *                         each piece one of them; {@code none} is the empty list
 */
public record Setting(String key, String label, String dflt, List<String> choices, boolean playerChangeable,
                      boolean list) {

    public Setting {
        choices = List.copyOf(choices);
    }

    public Setting(String key, String label, String dflt, List<String> choices, boolean playerChangeable) {
        this(key, label, dflt, choices, playerChangeable, false);
    }

    public boolean accepts(String value) {
        if (value == null) {
            return false;
        }
        if (choices.contains(value)) {
            return true;
        }
        if (!list || value.isBlank()) {
            return false;
        }
        for (String piece : value.split(",", -1)) {
            if (!choices.contains(piece.strip()) || "none".equals(piece.strip())) {
                return false;
            }
        }
        return true;
    }
}
