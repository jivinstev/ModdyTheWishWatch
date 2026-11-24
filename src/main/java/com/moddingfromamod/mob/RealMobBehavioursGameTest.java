package com.moddingfromamod.mob;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.design.EventBuild;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.vanilla.RigInstincts;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>A real mob takes behaviour the way a sculpted creature does, from the moment it arrives</b>
 *. Live, 2026-11-01: an army of twelve REAL endermen, staged for a child and made friendly
 * followers ten seconds later, stood by while zombies killed him twice — "fights for me" was not
 * something a real mob could be given — and four of them had teleported out of the change's 32-block
 * reach before it ran, so four wild endermen stood near him.
 *
 * <p>Driven through the real appliers: {@code ModifyMobTool.applyTo} (what {@code modify_mob},
 * {@code wishsummon} and a staged crowd all use), the real {@code modify_mob} tool call for the
 * {@code @event:} handle, and {@code EventBuild.nearPlayer} for the crowd.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RealMobBehavioursGameTest {

    /**
     * A friendly guardian ENDERMAN — a real one, not a sculpt — kills a zombie near its owner, and
     * never takes its owner as a target while it does. Under a roof: in daylight with the sky above
     * it an enderman teleports at random and a zombie burns, either of which would end the test for
     * a reason that has nothing to do with this.
     */
    @GameTest(template = "empty_test", batch = "realbehaviour_guard", timeoutTicks = 600)
    public static void aGuardianEndermanKillsAZombieAndNeverItsOwner(GameTestHelper helper) {
        int y = com.moddingfromamod.mob.PetBehavioursGameTest.floor(helper) + 1;
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, y + 4, z), Blocks.STONE);
            }
        }
        Player child = helper.makeMockPlayer(GameType.SURVIVAL);
        EnderMan soldier = helper.spawn(EntityType.ENDERMAN, new BlockPos(2, y, 2));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(6, y, 6));
        zombie.setPersistenceRequired();
        zombie.setTarget(child);

        // THE CONTROL: before it is given anything, the shared layer is not driving it at all.
        helper.assertFalse(RealMobBehaviours.isDriven(soldier), Component.literal("an ordinary enderman is not driven"));

        List<String> notes = ModifyMobTool.applyTo(helper.getLevel().getScoreboard(), soldier,
                MobTweak.fromJson(JsonParser.parseString(
                        "{\"friendly\":true,\"base\":\"guardian\",\"name\":\"Enderman Soldier\"}").getAsJsonObject()),
                child.getUUID());
        helper.assertTrue(RealMobBehaviours.isDriven(soldier), Component.literal("the profile did not land: " + notes));
        Species view = RealMobBehaviours.speciesFor(soldier);
        helper.assertTrue(view != null && view.base() == Species.Base.GUARDIAN, Component.literal("no guardian view: " + view));
        helper.assertTrue(view.damage() > 0, Component.literal("a real enderman hits with its own attack damage, not 0"));

        AtomicBoolean turnedOnHim = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (soldier.getTarget() instanceof Player || child.getUUID().equals(RigInstincts.targetOf(soldier))) {
                turnedOnHim.set(true);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertFalse(turnedOnHim.get(), Component.literal("the guardian took its OWNER as a target"));
            helper.assertTrue(soldier.isAlive(), Component.literal("the guardian itself died"));
            helper.assertTrue(!zombie.isAlive(), Component.literal("the zombie is still standing: " + zombie.getHealth()
                    + " health, guardian hunting " + RigInstincts.targetOf(soldier)
                    + ", " + String.format("%.1f", soldier.distanceTo(zombie)) + " blocks apart"));
        });
    }

    /**
     * A staged crowd of twelve real endermen with {@code traits} arrives ALREADY friendly guardian
     * followers — every one, as it spawns — and {@code modify_mob} with the {@code @event:} handle
     * reaches all twelve with a radius that could not reach one: the handle is the event's own tag,
     * not a distance.
     */
    @GameTest(template = "empty_test", batch = "mockplayer", timeoutTicks = 1200)
    public static void aStagedArmyArrivesWithItsTraitsAndTheHandleReachesEveryOne(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-army");
        EventDesign design = EventDesign.parse("{\"name\":\"test_army\",\"motion\":\"crowd\",\"size\":\"medium\","
                + "\"drop\":\"minecraft:enderman\",\"count\":12,\"pattern\":\"cluster\",\"traits\":{"
                + "\"friendly\":true,\"follow\":true,\"base\":\"guardian\",\"name\":\"Enderman Soldier\"}}").design();
        helper.assertTrue(design != null, Component.literal("the army design did not parse"));
        EventBuild.Result r = EventBuild.nearPlayer(child, design, child.getGameProfile().name());
        helper.assertTrue(r.ok(), Component.literal("the army was not staged: " + r.error()));
        String tag = EventDirector.entityTag(r.id());
        ServerLevel level = helper.getLevel();

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        AtomicBoolean asked = new AtomicBoolean();
        helper.succeedWhen(() -> {
            List<LivingEntity> army = new ArrayList<>();
            level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> e.getTags().contains(tag), army);
            helper.assertTrue(army.size() == 12, Component.literal("waiting for the twelve: " + army.size() + " so far"));
            for (LivingEntity e : army) {
                PetBehaviour pet = PetBehaviours.of(e);
                helper.assertTrue(Boolean.TRUE.equals(pet.friendly()) && Boolean.TRUE.equals(pet.follow()),
                        Component.literal("one arrived without its pet traits: " + PetBehaviours.stored(e)));
                helper.assertTrue(RealMobBehaviours.profileOf(e).base() == Species.Base.GUARDIAN,
                        Component.literal("one arrived without its guardian profile: " + RealMobBehaviours.stored(e)));
                helper.assertTrue(child.getUUID().toString().equals(PetBehaviours.ownerStored(e)),
                        Component.literal("one arrived as somebody else's pet: '" + PetBehaviours.ownerStored(e) + "'"));
            }
            if (asked.compareAndSet(false, true)) {
                Thread t = new Thread(() -> reply.set(new ModifyMobTool().call(JsonParser.parseString(
                        "{\"scope\":\"group\",\"target\":\"" + ModifyMobTool.EVENT_HANDLE + r.id() + "\","
                                + "\"radius\":1,\"count\":12,\"name\":\"Ender Knight\"}").getAsJsonObject())),
                        "realbehaviour-gametest");
                t.setDaemon(true);
                t.start();
            }
            ToolResult got = reply.get();
            helper.assertTrue(got != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(got.isError(), Component.literal("modify_mob @event: failed: " + got.text()));
            for (LivingEntity e : army) {
                helper.assertTrue(e.getCustomName() != null && "Ender Knight".equals(e.getCustomName().getString()),
                        Component.literal("the @event: handle missed one: " + e.getCustomName() + " — " + got.text()));
            }
        });
    }
}
