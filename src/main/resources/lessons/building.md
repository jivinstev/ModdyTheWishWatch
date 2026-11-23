# What we have learned about building things

Short, and every line is here because something went wrong without it.

## dx/dz are the LOW CORNER, not the middle

This is the one that has actually broken real buildings, twice.

A shape is drawn from `dx, dz` outwards, so **two shapes with the same `dx/dz` and different sizes
are not one inside the other** — they line up along one edge.

```
    wall  44 wide at dx:0   ->  x 0 .............................. 43
    air   40 wide at dx:0   ->  x 0 ...................... 39
                                ^ flush here            ^ 4 blocks of wall only on THIS side
```

The west and north walls are gone. The building looks fine from two sides and is open on the other
two, and nothing in the game will tell you — you cannot see your own build.

Two ways to do it properly, both fine:

- **`anchor: "centre"` on both shapes.** Then `dx/dz` is the middle of the footprint and the two
  really are concentric. `dy` is still the base in this mode, so the shape sits *on* `dy`, not
  halfway through it.
  **So centring never fixes the HEIGHT.** A hollow sphere and the air inside it at the same `dy`
  are not concentric: the smaller one sits on the same bottom, its bottom replaces the shell's, and
  a dome under water floods through the hole. Lower the outer shape by half the difference too — a
  19-wide glass sphere around a 17-wide air sphere at `dy:-21` goes at `dy:-22`.
- **Offset the inner shape by half the difference.** 44 and 40 differ by 4, so the inner one goes at
  `dx:2, dz:2`.

The same trap catches anything placed *relative* to a big shape — corner towers, pillars, a doorway.
Work out the corner, or set `anchor: "centre"` on everything and think in middles throughout. Do not
mix the two inside one building without checking.

## DOWN means below the spot: a negative `dy`

`dy 0` is the ground at the spot he aimed at, and `dy` goes negative. When he asks for something
that goes **down** — underground, a mine or a shaft, a basement or a cellar, a bunker, a well, steps
down — the dug part goes at **negative `dy`, below the spot**: a shaft 20 deep runs from `dy -20` up
to `dy 0`, with its ladder on the wall of the hole, and only the way in (a hatch, a little hut, a
fence round the top) stands above the ground. Built upward it is a tower, the opposite of what he
asked for — a real "going down" wish came out exactly that way. A hollow shape does not dig: the
room underground needs a `minecraft:air` shape inside its walls to clear the dirt out.

## A fence or low wall is a hollow box, and it stays a ring

A `hollow: true` box or cylinder one or two blocks tall in a real block is just its edge: a fence round a
garden, a pen or a playground is ONE hollow box of `oak_fence` 1 tall, and a round railing, battlement or
rim is ONE hollow cylinder 1 tall — no air shape to carve the middle out, it is already empty. (A short
hollow box or cylinder of `minecraft:air` still clears its whole area.)

## Later shapes overwrite earlier ones

That is the feature. A wall then a small box of `minecraft:air` through it is a doorway; a roof goes
on after the tower. But it is also how a wall gets eaten by accident — see above.

## Put a top on a tall thing

A hollow cylinder three or more tall keeps its top cap, so a bare tower is a tube with a lid on: from the air it reads
as an unfinished box. Anything taller than it is wide wants a `pyramid` on top, or a dome, or a
taper. A wide flat thing is different — an arena floor, a runway or a plaza is *supposed* to be flat,
so leave those alone.

## Leave a way IN — and cut it after the walls, not before

Measured over the 19 buildings really built for a child: **13 of them enclose a room with no way
in**, and one sealed 28,228 blocks of air. Not a rare slip — the common case.

It is invisible for the same reason the dark rooms were, only worse: every other signal reads
*healthier* when a building is sealed. It has four walls. It has a solid lid. It even gets LIT,
because the lighting check fires precisely when a building encloses a space. Everything says
finished, and the child walks round it and cannot get in.

So a doorway is not decoration, it is the thing that makes the rest of the building reachable.
**Send it as a small box of `minecraft:air` cut through the wall, and send it AFTER the wall
shape** — later shapes overwrite earlier ones, so a doorway sent first is simply built over by the
wall that follows. Same for windows, an archway, a hole in a roof, a stairwell between floors.

Do not settle for one hole in the outer wall either. Every room the child is meant to stand in
needs its own opening: a keep inside a courtyard, a tower off a hall, a basement. If a room is
genuinely meant to be solid — the inside of a spire, a plinth, a mountain — build it solid rather
than hollow, so nobody is left hunting for a door that was never there.

The build tool tells you when it finds one, as a ⚠ SEALED note with the size of the trapped space
and a spot on its surface to cut through. That note arrives AFTER the build; the doorway costs
nothing if it goes in the first time.

## A doorway he can WALK through

A way in is two blocks tall **from the floor he stands on**, and a `hollow: true` box has a bottom face:
walls as a hollow box standing on a floor slab lay a SECOND floor a block higher, so the doorway cut
from the walls' first layer is one block tall from inside and he cannot step up through it (four of the
Builder's own buildings shipped like that). Start the walls' box at the floor slab's own `dy`, or clear
the room with an air box after the walls. Every upper floor needs stairs or a ladder up to it.

Doors: a `minecraft:oak_door` shape 2 tall with no `half` is fine — I set its halves and facing as it
goes up. Never `iron_door`: no hand opens it, so I make it wooden. A plain doorway 1–2 wide in a house's
outside wall gets a matching wooden door. The tool's WALK-THROUGH note names any room he cannot reach
on foot and where; a solid turret left as decoration is fine.

## A band across a hollow tower is a FLOOR

This is the single commonest way a building ends up sealed, and it does not look like a mistake at
any point. **Nine of the fourteen sealed buildings on record are towers**, and in every one of them
the trapped rooms are the gaps between decorative bands.

A real one: a lighthouse 9 wide and 68 tall, with a pretty glass band ringing it every 8 blocks —
y=8, 16, 24, 32, 40, 48, 56, 64. Each band is an ordinary shape and each one looks lovely. Together
they cut the tower into **eight separate storeys of 148 blocks each**, none of them reachable from
the one below, and the child can climb none of it.

So when you ring a tower, or lay a shelf, a rim, a balcony floor or a stripe across the inside of
one:

- **leave a gap.** Make the band a ring that does not quite close, or set it one block narrower
  than the shaft so there is a slot beside it; or
- **cut a hole through each floor and put the ladder against the wall of the hole**,
  `minecraft:ladder[facing=…]` with `facing` pointing AWAY from that wall. A tower a child can
  climb is worth far more than a tower with tidy stripes.

**A ladder hangs on the block behind it.** `minecraft:ladder[facing=south]` hangs on the wall to
its NORTH; with nothing there it drops off the moment anything near it changes, and a column with
a gap in it cannot be climbed. A ladder standing in the middle of a hole, or out in the open,
has nothing to hang on, and a bare `minecraft:ladder` faces north whatever is around it. Run the
ladder up an inside wall (or the outside of a trunk or post) for its whole height, and let it
reach the floor it leads to. **Never climb on scaffolding, sand or gravel**: they fall unless
something is directly under them, so a scaffolding "ladder" up to a treehouse collapses the
first time the house is wished onto a tree or a slope.

A doorway does not help here. A hole in the outside wall of storey three leaves the other seven
exactly as they were — that is why the tool now says STACKED ONE ABOVE ANOTHER when it sees this
shape, rather than naming a doorway.

## Light every room you make

Nothing in the game tells you it is dark in there, and you cannot see your own building — so this
is the one thing you will never notice going wrong. A real child had to spend a whole wish asking
for it — candles, chandeliers, lanterns, anything — after the castle he had been given turned out
to be pitch dark inside.

An unlit room is not just gloomy. **Hostile mobs spawn at light level 0**, so a dark castle becomes
a creeper farm in the middle of the thing they are proudest of, and they will never work out why.

Lights are shapes like any other — a `minecraft:lantern` under the ceiling, `glowstone` or
`sea_lantern` set into a wall, a `campfire` in a hall. Space them out; one lantern does not light a
20-block room. If you forget, the build tool will hang a few plain lanterns for you and say so —
they are named `light_0`, `light_1` …, so you can replace them with something that suits the
building better by sending shapes with the same names.

**A roofed room gets lights of its own — IN that room.** A throne room, a hall, a shop, a
bedroom: if it has a roof over it, put lanterns, `glowstone`, `sea_lantern` or
`minecraft:candle[lit=true]` inside it, on its floor, walls or ceiling, as part of the same build
(a plain `candle` is placed unlit and gives no light). Light drops one level per block and stops at
any solid block, so a lantern on the floor ABOVE the ceiling, or at the far end of a long hall,
leaves the room black: a boss on his gold throne was photographed as a dark outline because the
castle's lanterns stood one storey up. `build_structure` works out the light and tells you
**DARK INSIDE** with the spot or the room and where it is; when it does, add lights there and build
again. Judge the light from that note, not from the photograph: the camera can see in the dark.

## Hollow means a room, solid means a rock

`hollow: true` for anything a child is meant to go inside or look into. A solid 24×14×24 box is not a
castle, it is a boulder — and it is 8,000 blocks instead of 2,000.

## Build big

This is a wish, not a shed. The budget is 220,000 blocks and 64 shapes; a good castle is 20–40
shapes. If you are refused for size, use hollow shapes before you make it smaller.

## Knocking down one of two towers: `ask_which` first

Removing or replacing a building cannot be undone by a sentence. If his words fit more than one of
his buildings, call `ask_which` with their ids BEFORE `plan_wish` — he picks from pictures — and
then plan with `about` set to his pick. If he picks none, change nothing and ask what he meant.
Never for a reversible change: "make my tower taller" with two towers is a guess you SAY in
`about`, not a question. Only a building the catalogue marks **standing** can be knocked down or
changed in place; one marked "NOT standing — design only" is not there (`ask_which` drops it, and
picks the one left without asking when only one stands).

## Changing a building they already have: `start_from`, never a second building

This is the one that was measured, and it was the biggest thing wrong with buildings.

Asked to add one feature to the top of a castle, an agent that had not built that castle raised a
**second building** beside it and left the castle alone. By the next wish the referent had drifted,
so a wish to make "it" taller grew the new thing instead. After five wishes the child had **four buildings** where he
asked for one castle. Measured across six ladder runs: refinements that actually landed on the
building were 3 in 4 for the worker who built it and **0 in 4** for anyone else.

So:

1. **`inspect_build <id>`** — what it is made of, how big it is, and every shape **by name**.
2. **`build_structure`** with the same `id`, `start_from` set to that id, and **only the shapes that
   change**.

Everything you do not mention is kept. A shape whose `name` matches one already in the building
**replaces it where it stands**; a new name is **added on top**; `remove: ["roof"]` deletes one.

```
   "give it a glass dome"   ->  start_from + ONE shape named "dome"
   "make it much taller"    ->  start_from + ONE shape named "keep", taller
   "take the roof off"      ->  start_from + remove: ["roof"]
```

**Name your shapes when you first build.** `keep`, `roof`, `west_tower`, `doorway`. It costs nothing
and it is the whole difference between the next wish being one shape and being a rebuild.

**A dome on the castle means the castle has a dome.** It does not mean the castle now has a
neighbour. Only build a genuinely separate structure when the child asks for a separate thing.

**Redoing it in the same wish? Use the SAME id.** A fix after "check it", or a better version
after the reveal, is the building you already put up: send `build_structure` again with **its id**,
and it is rebuilt where it stands. A second id leaves the first one standing, and he has two — a
real wish ended with two volcanoes and the next wish changed both. So a second building from nothing
in one wish is **refused, with nothing built**, until it is either the same id or says
`as_new: true` — which you send only when he asked for a separate building as well (a house AND a
tower).

## A creature IN the building goes on a SPOT — a boss on his throne

"A boss on his throne", "a guard at the gate", "a dragon on the roof", "a baker at her
counter", "a puppy in its kennel": the creature and the building are ONE wish, and the creature
goes on a named place in it. A building's **anchor is its foundations** — a creature put there is
inside the floor, and a wished T-Rex suffocated a minute after the reveal that way.

So **mark the spot when you build**, with `spots` on `build_structure`:

```
   "spots": [{"name":"throne", "dx":5, "dy":2, "dz":2, "facing":"south"}]
```

- The SAME offsets as the shapes, naming the block his **feet** stand in — **one ABOVE** the seat,
  floor or roof block (a gold throne at dy 1 → the spot is dy 2). Put the spot in the seat itself
  and the tool lifts it and says so.
- Name it what he called it: `throne`, `roof`, `gate`, `counter`, `ramp`, `bed`.
- Every building already has **`front`**, outside its entrance, without asking.
- `inspect_build` lists them; spots are KEPT on a `start_from` edit, and one sent again with the
  same name moves it.

A spot under a roof needs a light near it: a creature in a dark room is a dark outline to the
child and in his picture. Put a lantern or glowstone within a few blocks of the spot.

Then put the creature there with **`at: "<building id>:<spot>"`** — on `sculpt_creature`,
`define_creature`, or `wishmob spawn <id> at castle:throne`. It is placed **after the building has
finished going up**, on that spot, and checked like every other spawn. In a wish that built
something and names no spot, the creature stands at the building's `front` — never in its walls.

A REAL mob, or anything else, goes on a spot the same way: **`wishspot <building id>:<spot> run
<command>`** (any `at` works there: `wishspot me:front run …`) runs that one command standing on the spot (`~ ~ ~` is the spot, facing its way), after
the building is finished — `wishspot castle:roof run summon minecraft:ender_dragon ~ ~ ~ {NoAI:1b}`,
`wishspot shop:counter run setblock ~ ~ ~ minecraft:chest`. Never work out a spot's coordinates by
hand: they change every time the building is put up somewhere else.

## "On my roof", "in my house", "next to my castle": EVERY building already has spots

You do not have to have marked a spot to put something IN, ON or BY a building he already has.
Every building has these, worked out from its blocks — `inspect_build` lists them with `(automatic)`:

- `front` — outside its entrance · `top` (or `roof`) — standing on its highest part, nearest the middle
- `inside` — the floor of its main room (`inside_2`, `inside_3`… are the other rooms, ground floor first)
- `north` / `east` / `south` / `west` — outside that wall · `beside` — next to it, never across its
  doorway · `behind`
- `around` — a ring just outside it: a herd spreads round it, an event surrounds it

So when his words NAME a place — "a dragon on my roof", "put the cars in my garage", "snow falling over
my tower", "a meteor smashing into my castle", "build a stable next to my house", "paint a flag on my castle's
wall", "a dog in front of me" — pass **`at: "<thing>:<spot>"`** on the tool that makes it. The thing is
a building's id, a creature's id, or `me`. The SAME `at` works on every placing tool, and is checked the
same way: if the thing is not standing, the tool says so and why. Never look up a building's position
and type its coordinates: they are wrong the next time it is put up somewhere else.

A NEW building can be put by one he already has the same way — `build_structure` with `at`:
`beside`, `behind`, `front` or a compass side stands it OUTSIDE that side with a block of air between
the walls ("a stable next to my house" → `at: "<house id>:beside"`); `around`, `top` and `inside`
CENTRE it there ("a wall round my castle" → `at: "<castle id>:around"`, built wider than the castle;
"a lighthouse on my castle's roof" → `at: "<castle id>:top"`). Changing a building never moves it.

Teach the wish with the `at` in the creature's line (`wishbuild castle`, then `wishmob spawn boss
at castle:throne`) and a re-wish builds the castle and seats him again; his own button seats him
on the throne while the castle stands.

## The build tool answers back

`build_structure` returns a note when the result is not what you described — a size that got clamped,
shapes that were dropped, or **a room that ended up missing a wall**. Read it. If it says a wall is
gone and you did not mean it, fix the offsets and build again under the same `id`.

It will also tell you if a change **replaced the building instead of editing it** — if most of the
ground it stood on is no longer built on. That almost always means the shapes were re-described from
scratch instead of inherited with `start_from`. It is a note, not a refusal: if the child asked for
the old one to go, say so and carry on.

**A PICTURE on a wall is `paint_surface` — never coloured blocks.** A block face is about nine
pixels, so a face or a logo built out of blocks reads as a coloured smudge, however carefully it is
placed. `paint_surface` draws a real picture at thousands of pixels in the same space: give it the
bottom-left block, which way the picture LOOKS, and how many blocks wide and tall to hang it. One
call paints the whole picture however many blocks it covers. *(.)*

**Do NOT paint a background — anything you leave unpainted is SEE-THROUGH.** A picture on a
wall hangs on the face of it, and whatever you do not paint lets the wall show past.
Paint the shape itself and stop; a full-canvas rectangle underneath turns a face into a coloured
slab with a face on it, and it hides the building the child just watched you build.
*(2026-08-25: measured on a real train themed on a famous video-game villain. All 15 pictures came back 100% opaque with a solid
backdrop, because the tool's own example opened with one. Every picture sat on a flat coloured panel. Nothing failed — the pictures were exactly what had been asked for.)*

**Draw it BIG — the picture is the whole panel, not a stamp in the middle of it.** The subject
fills the picture edge to edge; only the space AROUND it is left unpainted. This is the other half
of "no background": the backdrop used to set the size by accident, so removing it without drawing
bigger just shrinks the face.
*(2026-08-25: measured. The tool's example was changed from a full-canvas rectangle to an 80x90
shape in a 256 picture, and the very next villain came back as a postage stamp on the nose of his
own train. Transparency worked perfectly and the result was WORSE than before.)*

## Give the button a PICTURE: pass `icon` to `add_template`

Every wish you teach becomes a button on Moddy's panel, and that button needs a picture. Pass
`icon` — a vanilla item id — and pick the thing a child would point at and say *"that one"*:
`minecraft:cake`, `minecraft:cow_spawn_egg`, `minecraft:bricks`, `minecraft:chest`.

Without it the picture is **guessed from the words**, and a recipe id you invented
(`sparkly_cheese_island`, `mega_lava_fort`) matches no keyword at all — so it lands on the same
amethyst shard as every other unmatched wish. A row of identical shards is not a menu: a child
scanning it is reading nothing, and has to hover every cell in turn to find out what any of them is.

It must be a **real** vanilla item id. A made-up one is dropped and the guess happens anyway, which
looks exactly like not passing one.

## TNT: at most 2,000 in one building — a real bang, never a server-stopper

`build_structure` refuses a building holding more than **2,000 TNT** (counted on the finished
building). Thousands of TNT going off at once make one server tick take over a minute: Minecraft
stops the whole server, EVERY child is thrown out, and the lit TNT is saved and goes off again when
it comes back. For a "volcano full of TNT", a "TNT bomb" or a "giant explosion" build a HOLLOW shell
of TNT, a core of up to 2,000, or a layer under the crater — never a solid TNT fill — and tell him
it is still a HUGE bang. The world also stops more than 2,048 TNT being lit at the same moment, so
a bigger pile would only fizzle part-way anyway.
*(2026-09-27,: a volcano "PACKED with TNT — over 47,000 blocks" was built and lit on the live
server, which crashed three times in six minutes and threw the child out each time.)*


## What it stands on is done FOR you — don't build foundations

`build_structure` looks at what is under the parts you put at **dy 0** (the floor, a trunk, the
foot of a ladder) and makes them meet the ground, as part of the building:
- **a slope or a dip** → a **footing**: those columns are filled down to the ground with blocks that
  match it (dirt under grass, stone on stone);
- **water** → a **little island** under it, sand at sea level with a beach round it;
- **a long drop** — aimed at a treetop, a cliff, the sky above the ground → it is **set down on the
  ground below**, the wish's spot glides there, and he is told where it went;
- **lava** → an obsidian footing.

So **never add your own foundation, pillars or raft** under a building: it is done, matched to the
ground, and `remove_build` takes it away with the building. The tool result says what it did — say
it to him in your own words ("I gave it a little island", "it's down on the hill below the tree").
Nothing is ever filled under parts you put up high (a treehouse's platform at dy 12 keeps its
height — its trunk carries it).

**To float a building on purpose** (a sky castle, a cloud house, a hovering ship), either put
every part above dy 0 (start the lowest shape at dy 1 or higher), or raise the spot itself with
`offsetY` above 0 or `conjure_at` with `up`. Either way it is left exactly where you put it.
`offsetY` lifts it FROM the spot he aimed at. Leave `offsetForward` out: when he aimed somewhere it
is ignored and the building goes up where he aimed, never in front of where he stands now.

## Name only what you PLACED — he is about to look at it

Whatever you tell him it has, he walks over to find. So the words you give him name only what is
**really standing there**: the blocks you placed, the creature you spawned, where it actually is.
Never a part of your plan you did not build (a spiral you never laid, loops that came out as
pillars), and never a creature you have not seen standing. Describe what you **built**, not what
you planned — and you describe it in `finish_wish`'s `say`, AFTER `finish_building` has handed you
the photograph, so look at the photograph first and describe **that**: if it came out as a blue box
on a slab with a red post, say a blue garage with a red post, not "a blue tower with an orange
spiral track". If it came out somewhere odd (underwater,
inside a hill), say so plainly. A smaller true sentence beats a bigger one he
disproves in ten seconds — and *"I couldn't fit the spiral yet"* is honest, not a failure.
*(2026-10-03: a garage was announced with a spiral track and a T-Rex — the photo had neither
— a track as three massive loops that were stacked pillars, and a carwash with brushes that was
built underwater.)*

## What you summon to KEEP gets `keep: true`; a test prop never does

When the wish ends, every non-living thing your `run_command` calls summoned — minecarts, boats,
armour stands, dropped items — is tidied away as a test prop (three cars parked "forever" in a
garage were deleted that way, right after the reveal promised they would stay). So the call that
summons something he should KEEP — a car, a boat at his dock, a scarecrow, a decoration — passes
`keep: true`. The call that summons a stand-in to test on (a NoAI pig, an armour stand to aim at, an
egg to feed a machine) leaves it out, and those are cleared for you. Keep the two in separate calls.
Anything you summon to TRY the wish on is a test prop even when he might enjoy it (a target, a mob to
hit): a present is what he asked for. And `keep` cannot keep a mob frozen with NoAI, a dropped item
or a thing in flight — a statue he asked for stays by its `CustomName`, and an item he should have
goes in his hand with `give`. A living mob with its AI on is never tidied, keep or not.
