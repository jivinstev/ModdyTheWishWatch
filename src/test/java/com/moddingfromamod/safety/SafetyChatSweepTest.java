package com.moddingfromamod.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.moddingfromamod.world.WorldPeople;

/**
 * The chat sweep's decision, every branch — and the one rule that matters most, that a person
 * in distress is never held back, not even during a break from chat.
 */
class SafetyChatSweepTest {

    private static final SafetyLexicon LEX = SafetyLexicon.load(new StringReader(
            "SEXUAL\tporn\tSEXUAL\nDANGER\tmeth\tDRUGS\nWELLBEING\thurt myself\tSELF_HARM\n"));
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static SafetyChatSweep.Decision on(String text, ChatStrikes s, UUID who, long now) {
        return SafetyChatSweep.decide(text, WorldPeople.Sweep.ON, LEX, s, who, now);
    }

    @Test
    void offDoesNothingAtAll() {
        ChatStrikes s = new ChatStrikes();
        for (String t : new String[]{"hello", "porn", "I want to hurt myself"}) {
            SafetyChatSweep.Decision d = SafetyChatSweep.decide(t, WorldPeople.Sweep.OFF, LEX, s, A, 0);
            assertEquals(SafetyChatSweep.Act.PASS, d.act());
            assertTrue(d.deliver());
            assertNull(d.logAction(), "off logs nothing");
            assertFalse(d.note());
        }
    }

    @Test
    void anOrdinaryLineGoesOutUntouchedAndUnlogged() {
        SafetyChatSweep.Decision d = on("anyone want to trade cobblestone?", new ChatStrikes(), A, 0);
        assertEquals(SafetyChatSweep.Act.PASS, d.act());
        assertTrue(d.deliver());
        assertNull(d.logAction());
        assertEquals("", d.tell());
    }

    @Test
    void aClearlyLineIsHeldBackTheSenderIsToldAndItIsLoggedWithoutItsWords() {
        SafetyChatSweep.Decision d = on("look at this porn", new ChatStrikes(), A, 0);
        assertEquals(SafetyChatSweep.Act.BLOCK, d.act());
        assertFalse(d.deliver());
        assertEquals(ChatWords.HELD, d.tell());
        assertEquals("BLOCK", d.logAction());
        assertEquals(SafetyVerdict.Band.CLEARLY, d.verdict().sexual());
    }

    @Test
    void theThirdHeldLineInTenMinutesStartsABreakAndTheBreakHoldsEverything() {
        ChatStrikes s = new ChatStrikes();
        assertEquals(SafetyChatSweep.Act.BLOCK, on("porn", s, A, 0).act());
        assertEquals(SafetyChatSweep.Act.BLOCK, on("meth", s, A, 60_000).act());
        SafetyChatSweep.Decision third = on("porn", s, A, 120_000);
        assertEquals(SafetyChatSweep.Act.MUTE, third.act());
        assertEquals("MUTE", third.logAction());
        assertEquals(ChatWords.sayBreakStarted(10), third.tell());
        SafetyChatSweep.Decision clean = on("sorry", s, A, 180_000);
        assertEquals(SafetyChatSweep.Act.MUTED, clean.act(), "a break holds even a clean line");
        assertFalse(clean.deliver());
        assertNull(clean.logAction(), "a held line during a break is not a new record");
        assertEquals(ChatWords.sayBreakContinues(9), clean.tell());
        assertEquals(SafetyChatSweep.Act.PASS, on("hello", s, B, 180_000).act(), "another player is untouched");
        assertEquals(SafetyChatSweep.Act.PASS, on("back again", s, A, 120_000 + 10 * 60_000).act(),
                "the break ends on time");
    }

    @Test
    void distressIsNeverHeldBackNotEvenDuringABreak() {
        ChatStrikes s = new ChatStrikes();
        SafetyChatSweep.Decision d = on("I just want to hurt myself", s, A, 0);
        assertEquals(SafetyChatSweep.Act.WELLBEING, d.act());
        assertTrue(d.deliver());
        assertTrue(d.note());
        assertEquals(ChatWords.WELLBEING, d.tell());
        assertEquals("WELLBEING", d.logAction());
        on("porn", s, A, 1);
        on("porn", s, A, 2);
        on("porn", s, A, 3);
        SafetyChatSweep.Decision muted = on("I want to hurt myself", s, A, 4);
        assertEquals(SafetyChatSweep.Act.WELLBEING, muted.act());
        assertTrue(muted.deliver(), "a break is about manners; this is a person");
    }

    @Test
    void shadowLogsWhatItWouldHaveDoneAndChangesNothingAnybodySees() {
        ChatStrikes s = new ChatStrikes();
        for (int i = 0; i < 5; i++) {
            SafetyChatSweep.Decision d = SafetyChatSweep.decide("porn", WorldPeople.Sweep.SHADOW, LEX, s, A, i);
            assertTrue(d.deliver());
            assertEquals("SHADOW_BLOCK", d.logAction());
            assertEquals("", d.tell());
        }
        assertEquals(0L, s.mutedUntil(A, 10), "shadow never strikes");
        SafetyChatSweep.Decision w = SafetyChatSweep.decide("hurt myself", WorldPeople.Sweep.SHADOW, LEX, s, A, 0);
        assertEquals("SHADOW_WELLBEING", w.logAction());
        assertTrue(w.note(), "the grown-up note is written in shadow too, like every other layer");
        assertEquals("", w.tell());
    }

    @Test
    void theChatCommandsAreReadAndNothingElseIs() {
        assertEquals(new SafetyChatSweep.Spoken(SafetyChatSweep.Surface.MSG, "hi there", "Bob"),
                SafetyChatSweep.spoken("msg Bob hi there").orElseThrow());
        assertEquals("hi", SafetyChatSweep.spoken("/minecraft:tell Bob hi").orElseThrow().text());
        assertEquals(SafetyChatSweep.Surface.MSG, SafetyChatSweep.spoken("w Bob x").orElseThrow().surface());
        assertEquals(new SafetyChatSweep.Spoken(SafetyChatSweep.Surface.MSG, "psst", "@a[name=Bob, limit=1]"),
                SafetyChatSweep.spoken("msg @a[name=Bob, limit=1] psst").orElseThrow());
        assertEquals(new SafetyChatSweep.Spoken(SafetyChatSweep.Surface.ME, "waves hello", ""),
                SafetyChatSweep.spoken("me waves hello").orElseThrow());
        assertEquals(SafetyChatSweep.Surface.TEAMMSG, SafetyChatSweep.spoken("tm go team").orElseThrow().surface());
        for (String other : new String[]{"msg Bob", "me", "give @p diamond", "moddy report Bob rude",
                "wish a pony", "say hello", "", "tellraw @a {\"text\":\"x\"}"}) {
            assertTrue(SafetyChatSweep.spoken(other).isEmpty(), other);
        }
    }

    @Test
    void theSurfaceIsTheRecordsToolField() {
        assertEquals("chat.chat", SafetyChatSweep.Surface.CHAT.label());
        assertEquals("chat.msg", SafetyChatSweep.Surface.MSG.label());
    }
}
