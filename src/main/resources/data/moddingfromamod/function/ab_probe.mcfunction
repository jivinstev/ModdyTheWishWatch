# Gate-B probe for docs/VANILLA_ITEM_SPIKE.md — never referenced by a wish.
#
# A wished item's effect is a datapack function, so "did the ability fire" can only be answered by
# running a real one. This places a block AT THE COMMAND SOURCE, which makes one assertion answer
# two questions at once: whether the function ran at all, and whether it ran WHERE it should have —
# at the player for a held item, at the target for on_hit, at the impact point for a throw. A flag
# on the player would answer only the first, and position is half of what these shapes promise.
setblock ~ ~ ~ minecraft:diamond_block
