# 1. the source's own clock. PERIOD_TICKS=1 means "as often as the trigger allows".
execute as @e[tag=wishmob_film_impulse] run scoreboard players add @s mfam_impt 1
# 2. when the clock is up, whoever the trigger catches starts a countdown
execute as @e[tag=wishmob_film_impulse,scores={mfam_impt=1..}] at @s run execute as @e[distance=..4,tag=!wishmob_film_impulse,type=!minecraft:player,type=!minecraft:item,type=!minecraft:arrow,type=!minecraft:armor_stand,type=!minecraft:block_display,type=!minecraft:item_display,type=!minecraft:text_display,type=!minecraft:interaction,type=!minecraft:marker,tag=!mfamrigpx] run scoreboard players set @s mfam_imp 6
# 3. TELEGRAPH: something has to happen where the push starts, or the movement has no cause
execute unless entity @e[tag=wishmob_film_impulse,tag=mfam_veiled,limit=1] as @e[tag=wishmob_film_impulse,scores={mfam_impt=1..}] at @s run particle minecraft:cloud ~ ~0.2 ~ 4 0.2 4 0.06 60 force
execute if entity @e[tag=wishmob_film_impulse,tag=mfam_veiled,limit=1] as @e[tag=wishmob_film_impulse,scores={mfam_impt=1..}] at @s run particle minecraft:cloud ~ ~0.2 ~ 4 0.2 4 0.06 60 force @a[tag=!mfam_veil_blind]
execute unless entity @e[tag=wishmob_film_impulse,tag=mfam_veiled,limit=1] as @e[tag=wishmob_film_impulse,scores={mfam_impt=1..}] at @s run playsound minecraft:entity.player.attack.knockback master @a ~ ~ ~ 1 1
execute if entity @e[tag=wishmob_film_impulse,tag=mfam_veiled,limit=1] as @e[tag=wishmob_film_impulse,scores={mfam_impt=1..}] at @s run playsound minecraft:entity.player.attack.knockback master @a[tag=!mfam_veil_blind] ~ ~ ~ 1 1
execute as @e[tag=wishmob_film_impulse,scores={mfam_impt=1..}] run scoreboard players set @s mfam_impt 0
# 4. LANDING: a big upward launch is lethal on the way down, which is not what anybody wished
#    for. For up-launches this hands out slow falling; otherwise it is a no-op.
execute as @e[scores={mfam_imp=6}] at @s run scoreboard players reset @s mfam_noop
# 5. one slice of the movement per tick, so it READS as a shove rather than a teleport
execute as @e[scores={mfam_imp=1..}] at @s run execute facing entity @e[tag=wishmob_film_impulse,limit=1] feet run tp @s ^ ^ ^-0.40
execute as @e[scores={mfam_imp=1..}] run scoreboard players remove @s mfam_imp 1