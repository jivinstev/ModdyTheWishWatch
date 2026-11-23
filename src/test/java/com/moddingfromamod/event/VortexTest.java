package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import com.moddingfromamod.event.catching.CatchRules;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.event.shape.Vortex;
import com.moddingfromamod.event.shape.VortexForces;
import com.moddingfromamod.event.shape.VortexLook;
import com.moddingfromamod.event.shape.VortexPath;
import org.junit.jupiter.api.Test;

/** The vortex (docs/EVENTS_PLAN.md §16): every rule in its spec, pure. */
class VortexTest {

    // ── 16.2: size is behaviour ──────────────────────────────────────────────────────────────────

    @Test
    void eachSizeLiftsHeavierThingsThanTheLast() {
        // Measured boxes (width, height) of vanilla 1.21.8 entities.
        assertEquals(Vortex.Body.LIGHT, Vortex.Body.of(0.4, 0.7), "a chicken");
        assertEquals(Vortex.Body.LIGHT, Vortex.Body.of(0.25, 0.25), "a dropped item");
        assertEquals(Vortex.Body.PERSON, Vortex.Body.of(0.6, 1.8), "him");
        assertEquals(Vortex.Body.PERSON, Vortex.Body.of(1.375, 0.5625), "a boat");
        assertEquals(Vortex.Body.PERSON, Vortex.Body.of(0.98, 0.7), "a minecart");
        assertEquals(Vortex.Body.PERSON, Vortex.Body.of(0.9, 1.4), "a cow");
        assertEquals(Vortex.Body.BIG, Vortex.Body.of(1.3965, 1.6), "a horse");
        assertEquals(Vortex.Body.HUGE, Vortex.Body.of(1.4, 2.7), "an iron golem");
        assertEquals(Vortex.Body.HUGE, Vortex.Body.of(1.95, 2.2), "a ravager");
        assertEquals(Vortex.Body.NEVER, Vortex.Body.of(16, 8), "an ender dragon's box");

        double boat = Vortex.Body.mass(1.375, 0.5625);
        assertEquals(Vortex.Body.PERSON, Vortex.Body.ofMass(boat + Vortex.Body.mass(0.4, 0.7)),
                "a boat that scooped up a chicken is still a boat (found in Gate B: a boat swallowed the test's chicken)");
        assertEquals(Vortex.Body.BIG, Vortex.Body.ofMass(boat + Vortex.Body.mass(0.6, 1.8)),
                "a boat with him in it is big: a medium tornado tugs it, a big one lifts it");

        Vortex.Body[] heaviest = {Vortex.Body.LIGHT, Vortex.Body.PERSON, Vortex.Body.BIG, Vortex.Body.HUGE};
        EventScale[] sizes = EventScale.values();
        for (int i = 0; i < sizes.length; i++) {
            Vortex.Size z = Vortex.size(sizes[i]);
            assertTrue(z.canLift(heaviest[i]), sizes[i] + " lifts " + heaviest[i]);
            if (i + 1 < heaviest.length) {
                assertFalse(z.canLift(heaviest[i + 1]), sizes[i] + " cannot lift " + heaviest[i + 1]);
            }
            assertFalse(z.canLift(Vortex.Body.NEVER), "nothing lifts a boss");
        }
        assertFalse(Vortex.size(EventScale.LITTLE).canLift(Vortex.Body.PERSON), "a dust devil cannot pick HIM up");
        assertTrue(Vortex.size(EventScale.MEDIUM).canLift(Vortex.Body.PERSON), "a tornado can: boats and minecarts too");
    }

    @Test
    void eachSizeTearsUpMoreKindsOfBlockFasterThanTheLast() {
        Vortex.Loose[] loosest = {Vortex.Loose.PLANT, Vortex.Loose.LOOSE, Vortex.Loose.GROUND, Vortex.Loose.BUILT};
        int lastRate = 0;
        double lastTop = 0;
        int lastHeight = 0;
        double lastSpeed = 0;
        for (EventScale s : EventScale.values()) {
            Vortex.Size z = Vortex.size(s);
            int i = s.ordinal();
            assertTrue(z.canTear(loosest[i]), s + " tears " + loosest[i]);
            if (i + 1 < loosest.length) {
                assertFalse(z.canTear(loosest[i + 1]), s + " never tears " + loosest[i + 1]);
            }
            assertFalse(z.canTear(Vortex.Loose.NEVER));
            assertTrue(z.tearsPerTick() > lastRate, s + " tears faster");
            assertTrue(z.top() > lastTop && z.height() > lastHeight && z.speed() > lastSpeed, s + " is bigger and faster");
            lastRate = z.tearsPerTick();
            lastTop = z.top();
            lastHeight = z.height();
            lastSpeed = z.speed();
            assertTrue(z.foot() < z.top(), "a funnel flares upward");
        }
        assertFalse(Vortex.size(EventScale.BIG).canTear(Vortex.Loose.BUILT), "only gigantic reaches his builds");
    }

    @Test
    void everySizeFitsItsBudgetAndCaps() {
        for (Vortex.Shape shape : Vortex.Shape.values()) {
            for (EventScale s : EventScale.values()) {
                Vortex.Size z = Vortex.size(s);
                List<VortexLook.Piece> p = VortexLook.pieces(shape, s);
                assertTrue(p.size() <= z.maxDisplays(), shape + " " + s + ": " + p.size() + " displays");
                assertTrue(p.size() >= 20, shape + " " + s + " is drawn with enough pieces to read: " + p.size());
                assertTrue(Vortex.reach(shape, s) <= s.radius, shape + " " + s + " reaches inside its scale");
                // Torn blocks over its life never come near the size's total budget.
                assertTrue((long) z.tearsPerTick() * z.lifeTicks() < s.totalBlocks, s + " tears inside its budget");
            }
        }
    }

    @Test
    void aWallStepIsSmallEnoughThatAGlideStaysOnItsRing() {
        // Displays are re-posed every 2 ticks with a 2-tick glide: the client moves them on a straight line, so
        // a step must stay a small angle or a piece cuts across the funnel instead of going round it.
        for (Vortex.Shape shape : Vortex.Shape.values()) {
            for (EventScale s : EventScale.values()) {
                for (VortexLook.Piece p : VortexLook.pieces(shape, s)) {
                    double step = Math.abs(p.spin()) * 2;
                    double sag = p.radius() * (1 - Math.cos(step / 2));
                    assertTrue(step <= 0.65, shape + " " + s + " step " + step);
                    assertTrue(sag < 0.6, shape + " " + s + " a glide sags " + sag + " blocks inside its ring");
                }
            }
        }
    }

    // ── 16.1: the defaults, so a preset is at most two fields ───────────────────────────────────

    @Test
    void theDefaultPathFollowsTheShapeAndSize() {
        assertEquals(Vortex.Path.STAY, Vortex.defaultPath(Vortex.Shape.DRAIN, EventScale.GIGANTIC), "a whirlpool stays");
        assertEquals(Vortex.Path.STAY, Vortex.defaultPath(Vortex.Shape.SPIRAL, EventScale.BIG));
        assertEquals(Vortex.Path.WANDER, Vortex.defaultPath(Vortex.Shape.FUNNEL, EventScale.LITTLE), "a dust devil dances");
        assertEquals(Vortex.Path.WANDER, Vortex.defaultPath(Vortex.Shape.FUNNEL, EventScale.MEDIUM));
        assertEquals(Vortex.Path.LINE, Vortex.defaultPath(Vortex.Shape.FUNNEL, EventScale.BIG));
        assertEquals(Vortex.Path.LINE, Vortex.defaultPath(Vortex.Shape.FUNNEL, EventScale.GIGANTIC),
                "a category 4 tornado cuts a swath");
    }

    @Test
    void aPresetIsAtMostAShapeAndAMaterial() {
        // The aim: "it doesn't take a ton of config to get it right". Each preset of EVENTS_PLAN §16.4 parses from
        // these fields alone and comes out as the variant it names.
        String[][] presets = {
                {"tornado", "{\"motion\":\"vortex\",\"size\":\"big\"}", "funnel", "line"},
                {"dust devil", "{\"motion\":\"vortex\",\"size\":\"little\",\"material\":\"sand\"}", "funnel", "wander"},
                {"whirlpool", "{\"motion\":\"vortex\",\"size\":\"medium\",\"shape\":\"drain\"}", "drain", "stay"},
                {"hurricane", "{\"motion\":\"vortex\",\"size\":\"gigantic\",\"shape\":\"spiral\"}", "spiral", "stay"},
                {"fire tornado", "{\"motion\":\"vortex\",\"size\":\"medium\",\"material\":\"orange_stained_glass\"}", "funnel", "wander"},
                {"quicksand", "{\"motion\":\"vortex\",\"size\":\"little\",\"shape\":\"drain\",\"material\":\"sand\"}", "drain", "stay"},
        };
        for (String[] p : presets) {
            EventDesign.Parsed r = EventDesign.parse("{\"name\":\"x\"," + p[1].substring(1));
            assertTrue(r.ok(), p[0] + ": " + r.errors());
            assertEquals(p[2], r.design().vortexShape().id(), p[0]);
            assertEquals(p[3], r.design().vortexPath().id(), p[0]);
            assertFalse(r.design().restore(), p[0] + " WRECKS by default: what it smashed stays until undo");
            assertTrue(r.design().wrecksBuilds() && r.design().hurtsCreatures(), p[0] + " wrecks and batters");
        }
        assertEquals(VortexLook.Family.SAND, VortexLook.Family.of("minecraft:sand"));
        assertEquals(VortexLook.Family.FIRE, VortexLook.Family.of("minecraft:orange_stained_glass"));
        assertEquals(VortexLook.Family.SAND, VortexLook.Family.of("minecraft:red_sand"), "red sand is sand, not fire");
        assertEquals(VortexLook.Family.WATER, VortexLook.defaultFamily(Vortex.Shape.DRAIN));
    }

    @Test
    void aDesignRoundTripsItsShapeAndPathAndRefusesThemElsewhere() {
        EventDesign d = EventDesign.parse("{\"name\":\"twin\",\"motion\":\"vortex\",\"size\":\"big\",\"shape\":\"funnel\","
                + "\"path\":\"circle\",\"count\":2,\"material\":\"pink_wool\"}").design();
        assertNotNull(d);
        EventDesign back = EventDesign.parse(d.toJson().toString()).design();
        assertEquals(d, back);
        assertEquals(Vortex.Path.CIRCLE, back.vortexPath());
        assertTrue(d.describe().contains("round and round"), d.describe());
        assertTrue(d.describe().contains("2 of them"), d.describe());

        EventDesign.Parsed wave = EventDesign.parse("{\"name\":\"w\",\"motion\":\"moving_front\",\"size\":\"big\","
                + "\"material\":\"water\",\"path\":\"line\"}");
        assertFalse(wave.ok(), "a path on a wave is refused, never silently dropped");
        EventDesign.Parsed bad = EventDesign.parse("{\"name\":\"v\",\"motion\":\"vortex\",\"size\":\"big\",\"path\":\"zigzag\"}");
        assertFalse(bad.ok());
        assertTrue(bad.errors().get(0).contains("stay"), "the refusal names the paths: " + bad.errors());

        EventDesign plain = EventDesign.parse("{\"name\":\"p\",\"motion\":\"vortex\",\"size\":\"medium\"}").design();
        assertNull(plain.shape(), "unsaid stays unsaid, so a later default change reaches kept designs");
        assertFalse(plain.toJson().has("path"));
        assertNull(EventDesign.parse("{\"name\":\"f\",\"motion\":\"layer\",\"size\":\"big\",\"material\":\"water\"}")
                .design().vortexPath());
    }

    @Test
    void aWreckerSparesOnlyWhatHisWordsSpare() {
        // A tornado or a wave wrecks by default; "safe for my builds" and "don't hurt the animals"
        // each turn one half off, and a design that spares his builds passes and gives the world back.
        EventDesign builds = EventDesign.parse("{\"name\":\"s\",\"motion\":\"vortex\",\"size\":\"big\",\"spares\":[\"builds\"]}").design();
        assertTrue(builds.restore() && !builds.wrecksBuilds() && builds.hurtsCreatures());
        EventDesign both = EventDesign.parse("{\"name\":\"s\",\"motion\":\"moving_front\",\"size\":\"big\","
                + "\"material\":\"water\",\"spares\":[\"builds\",\"creatures\"]}").design();
        assertTrue(both.restore() && !both.wrecksBuilds() && !both.hurtsCreatures(), "today's safe wave");
        EventDesign wave = EventDesign.parse("{\"name\":\"w\",\"motion\":\"moving_front\",\"size\":\"big\",\"material\":\"water\"}").design();
        assertFalse(wave.restore(), "a wave wrecks by default now");
        assertEquals(both, EventDesign.parse(both.toJson().toString()).design(), "round-trips");
        assertTrue(both.describe().contains("spares his builds") && both.describe().contains("never hurts"), both.describe());
        assertTrue(wave.describe().contains("SMASHES") && wave.describe().contains("never his pets"), wave.describe());
        EventDesign.Parsed flood = EventDesign.parse("{\"name\":\"f\",\"motion\":\"layer\",\"size\":\"big\","
                + "\"material\":\"water\",\"spares\":[\"builds\"]}");
        assertFalse(flood.ok(), "a flood does not wreck, so it has nothing to spare: refused, never ignored");
        EventDesign crater = EventDesign.parse("{\"name\":\"c\",\"motion\":\"impact_stamp\",\"size\":\"big\",\"material\":\"stone\"}").design();
        assertFalse(crater.hurtsCreatures(), "only the sweeping motions batter");
    }

    @Test
    void aLookIsSetOnlyWhenHisWordsNameOne() {
        EventDesign plain = EventDesign.parse("{\"name\":\"t\",\"motion\":\"vortex\",\"size\":\"big\"}").design();
        assertNull(plain.look(), "unsaid, the material's table decides when it is drawn");
        EventDesign ghost = EventDesign.parse("{\"name\":\"t\",\"motion\":\"vortex\",\"size\":\"big\",\"look\":\"streaks\"}").design();
        assertEquals(VortexLook.Style.STREAKS, ghost.look());
        assertEquals(ghost, EventDesign.parse(ghost.toJson().toString()).design(), "round-trips");
        assertTrue(ghost.describe().contains("whipping streaks"), ghost.describe());
        assertFalse(EventDesign.parse("{\"name\":\"t\",\"motion\":\"vortex\",\"size\":\"big\",\"look\":\"glitter\"}").ok(),
                "an unknown look is refused with the three names, never quietly drawn as something else");
        assertFalse(EventDesign.parse("{\"name\":\"m\",\"motion\":\"impact_stamp\",\"size\":\"big\",\"material\":\"stone\","
                + "\"look\":\"mist\"}").ok(), "a look is a vortex's");
    }

    @Test
    void wreckingReachesOneRowFurtherAndSparingTakesOnlyWhatTheWorldGrew() {
        Vortex.Size big = Vortex.size(EventScale.BIG);
        assertTrue(big.tears(Vortex.Loose.BUILT, true, false), "a wrecking big tornado takes his house");
        assertFalse(big.tears(Vortex.Loose.BUILT, false, false), "sparing his builds, never");
        assertFalse(big.tears(Vortex.Loose.LOOSE, false, false), "nor his glass (LOOSE but not grown)");
        assertTrue(big.tears(Vortex.Loose.GROUND, false, true), "a tree's log, yes");
        Vortex.Size little = Vortex.size(EventScale.LITTLE);
        assertTrue(little.tears(Vortex.Loose.LOOSE, true, false), "a wrecking dust devil takes sand and glass");
        assertFalse(little.tears(Vortex.Loose.GROUND, true, true), "but not a log");
        for (EventScale s : EventScale.values()) {
            assertFalse(Vortex.size(s).tears(Vortex.Loose.NEVER, true, true), "never a chest or the sea");
        }
    }

    @Test
    void aHurricanesWeatherIsLocalAndFades() {
        double reach = 20;
        assertEquals(1f, com.moddingfromamod.event.shape.VortexWeather.target(10, reach));
        assertEquals(1f, com.moddingfromamod.event.shape.VortexWeather.target(reach * 1.3, reach));
        assertEquals(0f, com.moddingfromamod.event.shape.VortexWeather.target(reach * 2.5, reach), "nothing past it: "
                + "the next island's sky is its own");
        float mid = com.moddingfromamod.event.shape.VortexWeather.target(reach * 1.9, reach);
        assertTrue(mid > 0.3f && mid < 0.7f, "it fades as he walks away: " + mid);
        float v = 0f;
        int ticks = 0;
        while (v < 1f) {
            v = com.moddingfromamod.event.shape.VortexWeather.step(v, 1f);
            ticks++;
        }
        assertTrue(ticks >= 20 && ticks <= 30, "the storm arrives over about a second: " + ticks);
    }

    @Test
    void aCycloneOfCatsLeavesItsCatsButAPlainOnePasses() {
        EventDesign cats = EventDesign.parse("{\"name\":\"c\",\"motion\":\"vortex\",\"size\":\"medium\",\"drop\":\"cat\","
                + "\"spares\":[\"builds\"]}").design();
        assertFalse(cats.restore(), "the cats land and stay until undo, even from a vortex that spares his builds");
        EventDesign told = EventDesign.parse("{\"name\":\"c\",\"motion\":\"vortex\",\"size\":\"medium\",\"drop\":\"cat\","
                + "\"afterwards\":\"restore\"}").design();
        assertTrue(told.restore(), "his words win");
    }

    // ── 16.1: paths ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aLineCrossesHisViewThroughTheSpotAtTheMiddleOfItsLife() {
        VortexPath.At spot = new VortexPath.At(100, 200);
        // He looks north (-z). His right is east (+x).
        VortexPath p = new VortexPath(Vortex.Path.LINE, spot, 0, -1, 0.2, 0, 0, 7);
        VortexPath.At start = p.at(0, 360), mid = p.at(180, 360), end = p.at(360, 360);
        assertTrue(start.x() < spot.x() && end.x() > spot.x(), "left to right across his view");
        assertEquals(spot.x(), mid.x(), 1e-9);
        assertEquals(spot.z(), start.z(), 1e-9, "never toward him or away");
        assertEquals(0.2 * 360, start.distanceTo(end), 1e-6, "a swath of speed × life");
    }

    @Test
    void aWanderDancesAboutTheSpotAndIsTheSameDanceEveryTime() {
        VortexPath.At spot = new VortexPath.At(0, 0);
        VortexPath a = new VortexPath(Vortex.Path.WANDER, spot, 0, 1, 0.12, 0, 10, 42);
        VortexPath b = new VortexPath(Vortex.Path.WANDER, spot, 0, 1, 0.12, 0, 10, 42);
        double far = 0, moved = 0;
        VortexPath.At last = spot;
        for (int t = 0; t <= 600; t++) {
            VortexPath.At x = a.at(t, 600);
            assertEquals(x, b.at(t, 600), "seeded");
            far = Math.max(far, x.distanceTo(spot));
            moved += x.distanceTo(last);
            assertTrue(x.distanceTo(last) <= 0.12 + 1e-9, "never a jump");
            last = x;
        }
        assertTrue(far <= 10 * 1.15, "it stays near where he aimed: " + far);
        assertTrue(far > 3, "but it does wander: " + far);
        assertTrue(moved > 60, "and keeps moving");
        VortexPath c = new VortexPath(Vortex.Path.WANDER, spot, 0, 1, 0.12, 0, 10, 43);
        assertFalse(c.at(300, 600).equals(a.at(300, 600)), "another seed, another dance");
    }

    @Test
    void aCircleKeepsItsRadiusAndAStayStays() {
        VortexPath.At spot = new VortexPath.At(5, 5);
        VortexPath c = new VortexPath(Vortex.Path.CIRCLE, spot, 0, 1, 0.2, 12, 0, 1);
        for (int t = 0; t < 400; t += 37) {
            assertEquals(12, c.at(t, 400).distanceTo(spot), 1e-9);
        }
        assertTrue(c.at(0, 400).distanceTo(c.at(100, 400)) > 5, "it goes round");
        VortexPath s = new VortexPath(Vortex.Path.STAY, spot, 0, 1, 0.2, 12, 0, 1);
        assertEquals(spot, s.at(300, 400));
    }

    @Test
    void aFollowerChasesButNeverRunsHimOver() {
        VortexPath.At at = new VortexPath.At(0, 0);
        VortexPath.At him = new VortexPath.At(10, 0);
        for (int i = 0; i < 200; i++) {
            at = VortexPath.chase(at, him, 0.2, 6);
        }
        assertEquals(6, at.distanceTo(him), 1e-9, "it stops its own width away");
        VortexPath.At once = VortexPath.chase(new VortexPath.At(0, 0), him, 0.2, 6);
        assertEquals(0.2, once.x(), 1e-9, "one step at its speed");
    }

    // ── 16.2: forces ────────────────────────────────────────────────────────────────────────────

    @Test
    void aFunnelSweepsRoundLiftsAndThrowsOutAtTheTop() {
        EventScale s = EventScale.MEDIUM;
        Vortex.Size z = Vortex.size(s);
        VortexForces.Push low = VortexForces.funnel(s, z.foot(), 1, 0, 0, true);
        assertTrue(low.caught() && !low.released());
        assertTrue(low.vy() > 0.3, "lifted");
        assertTrue(low.vy() <= CatchRules.MAX_LIFT, "never past the event lift cap");
        assertTrue(Math.abs(low.vz()) > 0.2, "swept round (tangential, at +x the wall moves along z)");
        VortexForces.Push top = VortexForces.funnel(s, Vortex.funnelRadius(z, z.height() * 0.9), z.height() * 0.9, 0, 0, true);
        assertTrue(top.released(), "thrown out of the top");
        assertTrue(top.vx() > 0.3, "outward");
        assertFalse(VortexForces.funnel(s, z.top() + 5, 1, 0, 0, true).caught(), "outside it, nothing");
        VortexForces.Push heavy = VortexForces.funnel(s, z.foot(), 1, 0, -0.08, false);
        assertTrue(heavy.caught() && heavy.vy() == -0.08, "too heavy: tugged sideways, never lifted");
        assertTrue(Math.hypot(heavy.vx(), heavy.vz()) <= 0.18 + 1e-9);
    }

    @Test
    void aForceReplacesTheVelocityItNeverAddsToIt() {
        // The same spot gives the same push whatever it was doing, so applying it every tick holds a speed.
        VortexForces.Push a = VortexForces.funnel(EventScale.BIG, 3, 4, 1, 0.0, true);
        VortexForces.Push b = VortexForces.funnel(EventScale.BIG, 3, 4, 1, 0.1, true);
        assertEquals(a.vx(), b.vx(), 1e-12);
        assertEquals(a.vz(), b.vz(), 1e-12);
        assertTrue(b.vy() >= a.vy(), "a lift is a floor, never a sum");
    }

    @Test
    void aDrainSinksThenSpitsOut() {
        EventScale s = EventScale.MEDIUM;
        VortexForces.Push in = VortexForces.drain(s, 3, 0.5, 0, 10, true);
        assertTrue(in.caught() && in.vy() < 0 && !in.released(), "drawn round and down");
        VortexForces.Push out = VortexForces.drain(s, 1, 2, 0, VortexForces.DRAIN_HOLD, true);
        assertTrue(out.released() && out.vy() > 0.5, "spat out upward");
        assertFalse(VortexForces.drain(s, 30, 0, 0, 0, true).caught());
    }

    @Test
    void aHurricaneIsStillInItsEyeAndWildestAtItsWall() {
        EventScale s = EventScale.GIGANTIC;
        double eye = Vortex.spiralEye(s);
        double reach = Vortex.spiralRadius(s);
        assertFalse(VortexForces.spiral(s, eye * 0.5, 1, 0, 0, true).caught(), "the eye is calm");
        double atWall = eye + (reach - eye) * 0.2;
        double wall = Math.hypot(VortexForces.spiral(s, atWall, 1, 0, 0, false).vx(),
                VortexForces.spiral(s, atWall, 1, 0, 0, false).vz());
        double edge = Math.hypot(VortexForces.spiral(s, reach * 0.95, 1, 0, 0, false).vx(),
                VortexForces.spiral(s, reach * 0.95, 1, 0, 0, false).vz());
        assertTrue(wall > edge * 2, "wall " + wall + " vs edge " + edge);
        assertTrue(VortexForces.spiral(s, atWall, 1, 0, 0, true).vy() > 0, "light things lifted at the wall");
        assertFalse(VortexForces.spiral(s, reach + 1, 1, 0, 0, true).caught());
    }

    @Test
    void debrisClimbsTheWallAndIsThrownOut() {
        double[] first = VortexLook.debris(Vortex.Shape.FUNNEL, EventScale.MEDIUM, 0, 0);
        double[] later = VortexLook.debris(Vortex.Shape.FUNNEL, EventScale.MEDIUM, 40, 0);
        assertNotNull(first);
        assertTrue(later[1] > first[1], "it rises");
        int gone = -1;
        for (int a = 0; a < 400; a++) {
            if (VortexLook.debris(Vortex.Shape.FUNNEL, EventScale.MEDIUM, a, 0) == null) {
                gone = a;
                break;
            }
        }
        assertTrue(gone > 40 && gone < 200, "thrown out of the top after a few seconds: " + gone);
        double[] down = VortexLook.debris(Vortex.Shape.DRAIN, EventScale.MEDIUM, 20, 0);
        assertTrue(down[1] < 0, "a drain swallows it");
    }

    @Test
    void theLookComesFromWhatItIsMadeOf() {
        // From a filmed comparison (EVENTS_PLAN §16.7).
        var F = Vortex.Shape.FUNNEL;
        assertEquals(VortexLook.Style.MIST, VortexLook.forVortex(F, VortexLook.Family.WIND), "a tornado is air you can see");
        assertEquals(VortexLook.Style.MIST, VortexLook.forVortex(F, VortexLook.Family.SAND), "a dust devil is a haze");
        assertEquals(VortexLook.Style.MIST, VortexLook.forVortex(F, VortexLook.Family.SNOW), "snow is flurries, not ice");
        assertEquals(VortexLook.Style.MIST, VortexLook.forVortex(F, VortexLook.Family.CANDY));
        assertEquals(VortexLook.Style.STREAKS, VortexLook.forVortex(F, VortexLook.Family.FIRE), "fire is dynamic");
        for (VortexLook.Family f : new VortexLook.Family[] {VortexLook.Family.WATER, VortexLook.Family.LEAVES,
                VortexLook.Family.ENDER, VortexLook.Family.OTHER}) {
            assertEquals(VortexLook.Style.SOLID, VortexLook.forVortex(F, f), f + " keeps its blocks");
        }
        assertEquals(VortexLook.Style.MIST, VortexLook.forVortex(Vortex.Shape.DRAIN, VortexLook.Family.WATER),
                "a whirlpool looked best as mist from under the water");
        assertEquals(VortexLook.Style.SOLID, VortexLook.forVortex(Vortex.Shape.SPIRAL, VortexLook.Family.CLOUD),
                "a hurricane's clouds look solid");
    }

    @Test
    void mistIsThinBandsAndStreaksThinnerStill() {
        VortexLook.Piece p = VortexLook.pieces(Vortex.Shape.FUNNEL, EventScale.MEDIUM).get(10);
        VortexLook.Piece mist = VortexLook.styled(p, VortexLook.Style.MIST);
        VortexLook.Piece streak = VortexLook.styled(p, VortexLook.Style.STREAKS);
        assertSame(p, VortexLook.styled(p, VortexLook.Style.SOLID));
        assertTrue(mist.along() > p.along() && mist.tall() < p.tall(), "a band is long and thin");
        assertTrue(streak.tall() < mist.tall() && streak.thick() < p.thick(), "a streak is thinner still");
        int c = VortexLook.mistColour(mist, VortexLook.Family.FIRE);
        int a = c >>> 24;
        assertTrue(a > 0x20 && a < 0xc0, "see-through, never solid: alpha " + a);
        int[] fire = VortexLook.tint(VortexLook.Family.FIRE);
        assertEquals(mist.core() ? fire[1] : fire[0], c & 0xffffff, "a fire band is in the fire's own colours");
    }
}
