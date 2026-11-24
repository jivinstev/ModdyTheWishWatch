package com.moddingfromamod.item;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Set;

/**
 * <b>"Stronger at certain times" — the half that touches the game.</b> Twice a
 * second, for each player: if the item in his main hand carries a {@code stronger_when} setting and
 * its time is now, a TRANSIENT modifier with our own id is on his attack damage or his mining speed;
 * otherwise it is off. Transient, so it is never saved — a world closed at night does not open at
 * noon with a sword still glowing with it — and one id, so it can never stack.
 *
 * <p>Vanilla does the rest: the hit is harder or the block breaks sooner exactly as if he had a
 * stronger item, which a stock client feels without any mod.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class StrongerWhen {

    /** E1's labels: the night sword's row names {@code night_strength} (syn-4-031). */
    public static final Set<String> PARTS = Set.of("night_strength", "stronger_when");

    static final Identifier ID =
            Identifier.fromNamespaceAndPath(ModdingFromAModMod.MODID, "wish_stronger_when");

    /** How often the check runs, in ticks: half a second is quicker than he can notice. */
    static final int PERIOD = 10;

    private StrongerWhen() {
    }

    /**
     * The SERVER's tick over every player, not each player's own: a player's tick is driven by his
     * connection, and the check must not depend on how he is connected (a gate's mock player never
     * runs one, which is how this was found).
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % PERIOD != 0) {
            return;
        }
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            apply(p);
        }
    }

    /** Put the modifier on or take it off, for what he holds and where he stands. */
    public static void apply(ServerPlayer p) {
        StrongerWhenRule.Setting s = WishItem.strongerWhen(p.getMainHandItem());
        boolean on = s != null && s.holds(world(p));
        for (Holder<Attribute> a : java.util.List.of(Attributes.ATTACK_DAMAGE, Attributes.BLOCK_BREAK_SPEED)) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) {
                continue;
            }
            boolean mine = on && a.equals(attribute(s.stat()));
            AttributeModifier have = inst.getModifier(ID);
            if (!mine) {
                if (have != null) {
                    inst.removeModifier(ID);
                }
                continue;
            }
            AttributeModifier want = new AttributeModifier(ID, s.modifier(),
                    "attack_damage".equals(s.stat()) ? AttributeModifier.Operation.ADD_VALUE
                            : AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
            if (have == null || have.amount() != want.amount() || have.operation() != want.operation()) {
                inst.removeModifier(ID);
                inst.addTransientModifier(want);
            }
        }
    }

    private static Holder<Attribute> attribute(String stat) {
        return "attack_damage".equals(stat) ? Attributes.ATTACK_DAMAGE : Attributes.BLOCK_BREAK_SPEED;
    }

    /**
     * ⚠ The CLOCK and the weather FLAGS, not {@code isNight()}/{@code isThundering()}: those read the
     * sky's darkness and a thunder level that ramp up over later ticks, so a sword switched at
     * midnight stayed weak until the sky caught up (measured: the GameTest read 1.0 both ways).
     * Night is vanilla's own window for monsters and beds, 13000–23000.
     */
    static StrongerWhenRule.World world(ServerPlayer p) {
        Level l = p.level();
        long t = Math.floorMod(com.moddingfromamod.mv.DayTime.get(l), 24000L);
        boolean night = l.dimensionType().hasSkyLight() && t >= 13000 && t < 23000;
        return new StrongerWhenRule.World(night, p.level().getWeatherData().isThundering(), p.level().getWeatherData().isRaining(),
                p.getBlockY(), l.dimension() == Level.NETHER, p.isUnderWater());
    }
}
