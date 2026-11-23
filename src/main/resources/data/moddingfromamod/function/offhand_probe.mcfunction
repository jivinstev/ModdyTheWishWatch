# Probe for the offhand click matrix (docs/OFFHAND_CLICK_MATRIX.md) — never
# referenced by a wish.
#
# "Did the main hand's ability fire" has to be answered by a real function, and in a FILM as well as
# in a GameTest: the gold block under him is what the server counts, the bell is what the film's
# audio track hears (a distinct note, unlike the stray -20..-35 dB blips both arms record), and the
# sparkles are what a frame shows.
setblock ~ ~-1 ~ minecraft:gold_block
playsound minecraft:block.note_block.bell player @a ~ ~ ~ 1 1.5
particle minecraft:happy_villager ~ ~1 ~ 0.6 0.6 0.6 0 20 force
