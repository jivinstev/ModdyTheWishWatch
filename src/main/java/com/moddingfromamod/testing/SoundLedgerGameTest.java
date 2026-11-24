package com.moddingfromamod.testing;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.seam.SoundLedger;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;

/**
 * <b>A sound is placed where its creature stood when it was SENT</b> (harness fault 19).
 *
 * <p>The behaviour exercise read the ledger at the end of each phase and measured every sound from
 * where the creature had got to by then. A small creature's death cry, played where it died, came
 * out 5.1 blocks from where its body ended up, so "it cries when it dies" scored "no sound from it"
 * on both models in every panel chunk 4 (bh-c06). Red if the ledger stops noting the
 * subject at send time.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SoundLedgerGameTest {

    @GameTest(template = "empty_test", batch = "sound_ledger_subject", timeoutTicks = 100)
    public static void aSoundIsPlacedWhereItsCreatureStoodWhenItWasSent(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        Cow cow = helper.spawn(EntityTypes.COW, new BlockPos(2, 2, 2));
        ServerPlayer kid = MockPlayerNetwork.inLevel(helper, "mfam-ledger-kid");
        SoundLedger.start();
        try {
            SoundLedger.follow(() -> new double[]{cow.getX(), cow.getY(), cow.getZ()});
            double x0 = cow.getX();
            double z0 = cow.getZ();
            SoundLedger.sent(kid, new ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BAT_DEATH), SoundSource.NEUTRAL,
                    x0, cow.getY(), z0, 1f, 1f, 0L));
            cow.teleportTo(x0 + 6, cow.getY(), z0);   // the body moves on before anyone reads the ledger
            // (a sound packet keeps its place to an eighth of a block, so "at it" is under a block)
            var got = SoundLedger.drain();
            helper.assertTrue(got.size() == 1, Component.literal("one sound was sent, the ledger holds " + got.size()));
            var s = got.get(0);
            double off = Math.hypot(s.x() - s.subjectX(), s.z() - s.subjectZ());
            helper.assertTrue(!Double.isNaN(off) && off < 1.0, Component.literal("the sound was made right at the "
                    + "creature, and the ledger places it " + off + " blocks from it"));
        } finally {
            SoundLedger.stop();
        }
        helper.succeed();
    }
}
