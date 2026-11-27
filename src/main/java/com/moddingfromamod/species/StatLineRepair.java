package com.moddingfromamod.species;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * <b>The stat-line repair: creatures already on players' shelves are put right too</b> (#104). Pure.
 *
 * <p>The creature tools used to say "Use 0 for a friendly character", so most creatures players made carry an attack
 * damage of 0: a guard that can never hurt what it guards against, a friendly creature whose moves and any world that
 * enlists it do nothing. #99 and #100 put right the stock creatures and the ones with no numbers of their own; this is
 * the rest.
 *
 * <p><b>Which records</b>: only those saved under the old tool wording, which is what a record version below
 * {@link #VERSION} means ({@code Species.recordVersion}); never inferred from a record's values. A record at or above
 * it was made under #101's wording and is never touched, whatever its damage. A repaired or read record is written
 * back at the current version at the store's next save, so the repair runs once per record. Registered in
 * {@code docs/STORE_MIGRATIONS.md}.
 *
 * <p><b>Exactly this and no more</b>: damage 0 on a creature with the {@code guardian} or {@code hunter} instinct, or
 * with the {@code friendly} power, becomes its size band's damage ({@link PowerBand}, measured on the body it draws).
 * A friendly creature still never attacks; its moves, and any world that enlists it, then work. A vehicle keeps its 0
 * (a vehicle never hits). Its health, look, name, powers, moves and temper are never touched, and a record it does not
 * repair is kept exactly as read.
 */
public final class StatLineRepair {

    /** The record version that carries this repair: a record below it is repaired; one at or above it never is. */
    public static final int VERSION = 3;

    /** What a repair did to a store's records: the records as they now are, and which changed. */
    public record Result(JsonArray records, List<String> repaired) {
    }

    private StatLineRepair() {
    }

    /**
     * One stored creature, repaired, or {@code null} when it needs nothing (then it is kept exactly as read).
     * {@code bodyVolume} is its body's solid volume at scale 1 (NaN when it draws none: its model shape's is used).
     */
    public static JsonObject repair(JsonObject record, double bodyVolume) {
        if (record == null || Species.recordVersion(record) >= VERSION) {
            return null;
        }
        Species s = Species.fromJson(record);
        if (s == null || !record.has("damage") || !record.get("damage").isJsonPrimitive()) {
            return null;   // no damage of its own is #100's band at read, not this; an unreadable record is the reader's
        }
        double damage;
        try {
            damage = record.get("damage").getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
        boolean fighter = s.base() == Species.Base.GUARDIAN || s.base() == Species.Base.HUNTER;
        boolean friendly = s.powers().contains(Species.Power.FRIENDLY);
        if (damage != 0 || !(fighter || friendly) || s.archetype() == Archetype.VEHICLE) {
            return null;
        }
        JsonObject out = record.deepCopy();
        PowerBand.Band band = PowerBand.forCreature(bodyVolume, s.body(), s.scale());
        out.add("damage", new JsonPrimitive(PowerBand.defaultDamage(band, false)));
        return out;
    }

    /** Every record of a store's array, repaired where it needs it; the rest are the very elements it was given. */
    public static Result repairAll(JsonArray records, ToDoubleFunction<JsonObject> bodyVolume) {
        JsonArray out = new JsonArray();
        List<String> repaired = new ArrayList<>();
        if (records == null) {
            return new Result(out, repaired);
        }
        for (JsonElement el : records) {
            JsonObject fixed = el.isJsonObject() ? repair(el.getAsJsonObject(), bodyVolume.applyAsDouble(el.getAsJsonObject()))
                    : null;
            if (fixed == null) {
                out.add(el);
            } else {
                out.add(fixed);
                repaired.add(fixed.has("id") ? fixed.get("id").getAsString() : "?");
            }
        }
        return new Result(out, repaired);
    }

    /** How many records are below the current record version: each is written back at it at the next save. */
    public static int below(JsonArray records, int current) {
        int n = 0;
        if (records != null) {
            for (JsonElement el : records) {
                if (el.isJsonObject() && Species.recordVersion(el.getAsJsonObject()) < current) {
                    n++;
                }
            }
        }
        return n;
    }
}
