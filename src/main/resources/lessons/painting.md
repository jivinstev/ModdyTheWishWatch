# What we have learned about pictures

Short, and every line is here because something went wrong without it.

## A picture OF a named real thing: look it up first, never paint from memory

A picture of a famous Minecraft YouTuber was once painted from memory — 17 rectangles, brown hair and a red shirt —
and looked nothing like him. When the thing in the picture has a NAME — a character from a game,
a cartoon or a TV show, a brand's mascot, or a real person ("a painting of Captain Comet", "a picture
of a YouTuber by his channel name") — even one you are sure you know:

1. **`look_up_character`** with the name, BEFORE `paint_surface`. For a character it returns the
   real colours and their proportions — paint with those.
   If `paint_surface` then says `MAIN COLOUR WRONG`, repaint the same id with that main colour before
   he sees it: a stand-in keeps the colours a child recognises.
2. **A real person is painted as their MINECRAFT SKIN**: `paint_surface` with `from_skin` = **the
   name they are known by** (e.g. `BlockyBob`) and **no `picture`**. **Never a username**, even one
   you are sure of: the account named exactly like a famous creator is often a stranger's — the creator plays under a different username —
   so a username guessed from the public name paints somebody else's skin and calls it him
. The server paints only the account an outside source (minecraft.wiki's person page,
   or the generated list of Minecraft creators) LINKS to the person, pixel for pixel. `skin_view` `face` (the default) is their head; `front` is
   the whole player. Never draw a real person's face from memory or from a photo.
3. **No linked account → no skin, and that is normal.** The tool says so and fetches nothing. Then
   paint them from `look_up_character`'s colours and description — it looks up what they look like
   in Minecraft — and tell the child it is your picture of them, not their real skin.
4. **Only public figures.** The server checks for a Wikipedia article (or a minecraft.wiki page
   naming their account) and refuses anyone else — a classmate's or a sibling's name gets no lookup
   and no skin. Then build from the child's words, in cartoon style, and do not try to make it look
   like the real person. **Except a creator on the generated list**: `look_up_character` then
   says "paint this creator's own skin" — do exactly that with `from_skin`. The list licenses that
   skin and nothing else about them.
5. If the linked player cannot be found, say so plainly. Never invent a skin.

## On a wall he NAMED: `at`

"This wall" is where he is looking, so leave x/y/z out and it hangs where he aimed. A wall of a
building he NAMED, one he is not looking at, is **`at: "<building id>:<wall>"`**, and his words pick
the wall: none means the front; a SIDE of it is `beside` (or `east`/`west`/`north`/`south`) and never
the front; its back is `behind`. It finds a flat patch
of that wall the picture's size, at his eye height, middle first; if the wall has none (towers and
gaps), it says so — pick a smaller picture or another side. Never work out the wall's coordinates
yourself.

## The picture is square and is stretched over the board

A picture is drawn on a square canvas and stretched to fill `wide` x `tall`. A face, a logo or
anything round goes on a **square board** (wide = tall); on an 8 by 6 board it comes out a third too
wide. `from_skin` sets the board's shape for you (a face square, a whole player twice as tall as wide).

## Changing a picture he has: `start_from`, never a redraw

Until a picture could not be read back, so "make the sun on my picture bigger" had one move
left: draw the whole picture again from nothing. Everything not described a second time was lost —
the hat, the clouds, the exact green he liked.

1. **`list_paintings`** — his pictures, newest first; the top one is the one he was working on.
2. **`inspect_painting`** — one picture shape by shape, so you can aim at "the sun".
3. **`paint_surface`** with `start_from` = its id and **only the shapes that change**. They are
   painted ON TOP of what is there, so "a bigger sun" is one bigger sun rectangle. It stays where it
   hangs.

## A new picture becomes a button: teach it

Once a NEW picture is hung, call `add_template` with commands `["wishpaint <its id>"]` and icon
`minecraft:painting`, the same as a machine or a creature. Without it he cannot hang his picture
again without a whole wish; with it, it is one tap on the watch. A CHANGE to a picture he already
has needs no new button — the one he has hangs the changed picture.

## "Another one like it" is `as_new`

"Make another one but at night" is a SECOND picture: `start_from` = the first, `as_new: true`, a new
`id`. It gets its own board; the first is never painted over.

## His brother's picture, and the built-in pictures, are never painted over

If the picture he names is another child's, or a built-in starter picture, `paint_surface` makes
**his own copy** on a new board in front of him and leaves the original alone. That is automatic —
do not pick a different id to get around it, and tell him plainly: "I made you your own copy!"

## A picture from another world

His pictures follow him. `list_paintings` shows ones that are "not hanging here yet"; hang one with
`start_from` and **no x/y/z** — it goes up on a new board where the wish was aimed.

## The three limits, and each one is SAID when it is hit

- **Shapes:** a picture keeps at most 1024 rectangles. Draw it with fewer, bigger rectangles, one per
  area of colour, never one per pixel. Sending more is refused and nothing is painted; a change on a
  picture already that big pushes its oldest shapes out, never the change.
- **Size:** a board is at most 16 by 16 blocks. Ask for more and it is hung at 16, and the result
  says so. Tell him if the size mattered.
- **A wall:** a picture hangs on something. With no wall behind it, it is refused rather than left
  floating in the air.
- **No solid backdrop:** do not paint a background rectangle across the whole canvas. Whatever you
  leave out is see-through, so his picture sits on the wall or the train, not on a flat slab.
