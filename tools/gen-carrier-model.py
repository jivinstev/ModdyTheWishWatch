import json, re, pathlib
# the jar's item definitions are generated from the SAME binding table the runtime pack is
# written from (item/IconSprites.bindings() + vanilla/AtomLibrary.all(), walked by
# VanillaItemPack.collectBindings) -- never from an `overrides` table. Until this script read
# wish_item.json's `overrides` and wrote the jar its own pre-1.21.4 carrier model,
# assets/minecraft/models/item/leather_horse_armor.json; refuses every client that could read
# either, so both are gone. CarrierModelDriftTest fails if what this writes ever differs from what
# VanillaItemPack writes for the pushed pack (namespace aside).
JAVA = pathlib.Path('src/main/java/com/moddingfromamod')
MODELS_DIR = pathlib.Path('src/main/resources/assets/moddingfromamod/models/item')

# THE ORIGINAL 27, 1..27: IconSprites.LIBRARY, where position is the number (APPEND ONLY). Read out
# of the Java source rather than typed again here, so there is one list.
FIRST = 1
_icon_src = (JAVA / 'item/IconSprites.java').read_text()
_m = re.search(r'List<String> LIBRARY = List\.of\((.*?)\);', _icon_src, re.S)
if not _m:
    raise SystemExit('IconSprites.LIBRARY not found -- the generator reads it from the source')
LIBRARY = re.findall(r'"([a-z0-9_]+)"', _m.group(1))
# (number, model id, band) -- the band is IconSprites.Band, which decides who is composed.
bindings = [(FIRST + i, 'moddingfromamod:item/wish/' + n, 'WISH')
            for i, n in enumerate(LIBRARY) if not n.startswith('retired_')]

# THE DRAWN LIBRARY, 28+: items/icon-library.json, the file IconSprites.drawn() reads. Each
# binds its FULL model; the one-tint `_flat` twin went with the 1.21.1 table that bound it.
for row in json.loads(pathlib.Path('src/main/resources/items/icon-library.json').read_text())['icons']:
    bindings.append((row['number'], 'moddingfromamod:item/wish/' + row['name'], 'WISH'))

# MODDY, the wish anvil's "Wish!" button and the Wish Bag, at their reserved
# numbers. Mirrors VanillaCarrier.WATCH_* / WISH_WAND_* / WISH_BAG_* / BACK_ARROW_*. Kept out of the wish band on
# purpose: a wished thing that came back looking like the thing you wish WITH is unreadable.
for cmd, icon in ((1000, 'wish_watch'), (1001, 'wish_wand'), (1002, 'wish_bag'), (1003, 'back_arrow'), (1004, 'light_bulb')):
    if not (MODELS_DIR / (icon + '.json')).exists():
        raise SystemExit('no %s model -- it would render as the carrier' % icon)
    bindings.append((cmd, 'moddingfromamod:item/' + icon, 'OWN'))

# THE ATOM RIG'S CONSTANT LIBRARY, at its own band. Mirrors VanillaCarrier.ATOM_* and
# AtomLibrary.all(): the two plain cubes, then the PATTERN cubes at 2002+ in the alphabetical order
# AtomLibrary.PATTERNS is sorted into. DERIVED from the shipped models, so adding a pattern cube is
# one edit in gen-atom-library.py. (`rig_atom_flat` is the unshaded cube, not a twin.)
ATOM_MODEL_DATA_FIRST = 2000
ATOM_MODELS = [(ATOM_MODEL_DATA_FIRST, 'rig_atom'), (ATOM_MODEL_DATA_FIRST + 1, 'rig_atom_flat')]
patterns = sorted(f.stem for f in MODELS_DIR.glob('rig_atom_*.json') if f.stem != 'rig_atom_flat')
if not patterns:
    raise SystemExit('no rig_atom_<pattern> models -- run tools/gen-atom-library.py first')
ATOM_MODELS += [(ATOM_MODEL_DATA_FIRST + 2 + i, m) for i, m in enumerate(patterns)]
for cmd, model in ATOM_MODELS:
    if not (MODELS_DIR / (model + '.json')).exists():
        raise SystemExit('no atom model %s -- run tools/gen-atom-library.py first' % model)
    bindings.append((cmd, 'moddingfromamod:item/' + model, 'ATOM'))

bindings.sort(key=lambda b: b[0])
for a, b in zip(bindings, bindings[1:]):
    if a[0] == b[0]:
        raise SystemExit('two bindings share custom_model_data %d: %s and %s' % (a[0], a[1], b[1]))
for _, model, _ in bindings:
    if not (MODELS_DIR / (model.split(':item/')[1] + '.json')).exists():
        raise SystemExit('binding %s names a model the jar does not ship' % model)

# The jar ships NO assets/minecraft/models/item/<carrier>.json any more: a plain carrier
# draws vanilla's own model through the definition's fallback, exactly as in the pushed pack.
_stale = pathlib.Path('src/main/resources/assets/minecraft/models/item/leather_horse_armor.json')
if _stale.exists():
    _stale.unlink()

# ── The NATIVE half, for a client that HAS the mod on 1.21.4 ──────────────────────────────
# 1.21.4 stopped reading `overrides`. A new stack names its own picture with minecraft:item_model =
# wishpack:icon/<name> (pack/NativeItemModels.java), so the jar ships one definition per
# binding, pointing at the jar's OWN model -- the same id the pushed pack defines for a stock client,
# pointing at the pack's copy. And an OLD stack, carrying only the number, needs the carrier's own
# definition to be a range_dispatch over the same table: that is the jar's half of the SHIM
# (docs/COMPAT_SHIMS.md). Both come from `bindings` above, so they cannot disagree with each other,
# and CarrierModelDriftTest holds both to what the runtime pack writes.
LEATHER_DYE_DEFAULT = -6265536


# a library icon with a SECOND colour has three layers -- body (dye), accent, detail -- and
# the accent is tinted from custom_model_data.colors[0]. Mirrors VanillaItemPack.ACCENT_DEFAULT; a
# stack with no colors (one minted before accents, or by a 1.21.1 server) draws it in this cream.
ACCENT_DEFAULT = 0xF4E8CC


def has_accent(model):
    """True when the model's layer1 is an `_accent` sprite (tools/gen-library-icons.py)."""
    f = MODELS_DIR / (model.split(':item/')[-1] + '.json')
    if not f.exists():
        return False
    return json.loads(f.read_text()).get('textures', {}).get('layer1', '').endswith('_accent')


def node(model):
    tints = [{"type": "minecraft:dye", "default": LEATHER_DYE_DEFAULT}]
    if has_accent(model):
        tints.append({"type": "minecraft:custom_model_data", "index": 0, "default": ACCENT_DEFAULT})
    return {"type": "minecraft:model", "model": model, "tints": tints}


def binding_name(model):
    # moddingfromamod:item/wish/cup -> cup ; moddingfromamod:item/rig_atom -> rig_atom
    return model.split('/')[-1]


ITEMS = pathlib.Path('src/main/resources/assets/wishpack/items/icon')
if ITEMS.exists():
    for old in ITEMS.glob('*.json'):
        old.unlink()
ITEMS.mkdir(parents=True, exist_ok=True)
# An ICON is composed -- its dyed sprite, and over it the overlay named by
# custom_model_data.strings[0] (item/IconOverlays.java), or nothing. Same shape as
# VanillaItemPack.composed, pointing at the jar's own overlay models (tools/gen-overlay-sprites.py).
OVERLAYS = sorted(f.stem for f in pathlib.Path(
    'src/main/resources/assets/moddingfromamod/textures/item/overlay').glob('*.png'))


BASES = json.loads(pathlib.Path('src/main/resources/items/icon-bases.json').read_text())["bases"]
BACKING_DEFAULT = 0xFFFFE070 - (1 << 32)   # VanillaItemPack.BACKING_DEFAULT, as the signed int it prints


def composed(base):
    # Behind the item, the sparkle backing -- a glow in his dye colour, then white twinkles.
    backing = [{"type": "minecraft:model", "model": "moddingfromamod:item/fx/backing_glow",
                "tints": [{"type": "minecraft:dye", "default": BACKING_DEFAULT}]},
               {"type": "minecraft:model", "model": "moddingfromamod:item/fx/backing_twinkle"}]
    back, front = badge_layers()
    return {"type": "minecraft:composite", "models": backing + back + [base, {
        "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
        "cases": [{"when": o, "model": {"type": "minecraft:model",
                                        "model": "moddingfromamod:item/overlay/" + o}}
                  for o in OVERLAYS],
        "fallback": {"type": "minecraft:empty"}}] + front}


# the Moddy badge a HOTBAR wish wears (pack/HotbarBadge.java, tools/gen-hotbar-badge.py): a condition on
# custom_model_data.flags[0], which only the hotbar proxy sets, so the history's pictures are unchanged.
BADGE_LOOK = json.loads(pathlib.Path('src/main/resources/items/hotbar-badge.json').read_text())['look']


def badge_layers():
    def flagged(sprite):
        return [{"type": "minecraft:condition", "property": "minecraft:custom_model_data", "index": 0,
                 "on_true": {"type": "minecraft:model", "model": "moddingfromamod:item/" + sprite},
                 "on_false": {"type": "minecraft:empty"}}]
    return (flagged('hotbar/badge_back') if BADGE_LOOK in ('inside', 'behind') else [],
            flagged('hotbar/badge') if BADGE_LOOK in ('corner', 'inside') else [])


def drawn(model):
    """What one binding draws -- the SAME node for its native definition and its number."""
    if '/wish/' not in model:
        return node(model)
    vanilla = BASES.get(binding_name(model))
    return composed({"type": "minecraft:model", "model": vanilla} if vanilla else node(model))


for _, model, _ in bindings:
    (ITEMS / (binding_name(model) + '.json')).write_text(
        json.dumps({"model": drawn(model)}, indent=2) + "\n")

carrier_def = {
    "model": {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:custom_model_data",
        "index": 0,
        "entries": [{"threshold": cmd, "model": drawn(model)} for cmd, model, _ in bindings],
        "fallback": node("minecraft:item/leather_horse_armor"),
    }
}
p = pathlib.Path('src/main/resources/assets/minecraft/items/leather_horse_armor.json')
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text(json.dumps(carrier_def, indent=2) + "\n")
print("native definitions:", len(bindings))

# ──: the mod's OWN items need a 1.21.4 definition too ─────────────────────────────────────────
# 1.21.4 draws EVERY item through assets/<ns>/items/<id>.json, including an item registered in Java.
# Nothing wrote one for ours, so a client that HAS the mod on 1.21.4 logged
#   "No model loaded for default item ID moddingfromamod:wish_watch"
# and drew Moddy's watch -- and the wish egg, the paint panel -- as the magenta missing cube (photographed
# in a real-agent run, hotbar slot 1). A stock child never sees these ids (he gets the carrier), so no
# stock-client gate could. The ids are READ from the registration source, never listed here, so a new
# item cannot ship without its definition: ModItemDefinitionsTest fails by name if one is missing.
def registered_item_ids():
    ids = []
    for f in sorted(JAVA.rglob('*.java')):
        src = f.read_text()
        ids += re.findall(r'registerItem\(\s*"([a-z0-9_]+)"', src)
        consts = dict(re.findall(r'static final String ([A-Z_]+)\s*=\s*"([a-z0-9_]+)"', src))
        for tok in re.findall(r'registerSimpleBlockItem\(\s*([A-Za-z_"0-9]+)', src):
            ids.append(tok.strip('"') if tok.startswith('"') else consts.get(tok, tok))
    return sorted(set(ids))


MOD_ITEMS = pathlib.Path('src/main/resources/assets/moddingfromamod/items')
MOD_ITEMS.mkdir(parents=True, exist_ok=True)
for item_id in registered_item_ids():
    model = MODELS_DIR / (item_id + '.json')
    if not model.exists():
        raise SystemExit('registered item %s has no models/item/%s.json' % (item_id, item_id))
    base = {"type": "minecraft:model", "model": "moddingfromamod:item/" + item_id}
    if item_id == 'wish_item':
        # The mod's own wish item: the WISH band of the same table (no Moddy, no atoms), dyed like
        # the carrier. Its model wish_item.json carries no `overrides` -- this
        # definition is the only thing that ever bound its numbers on 1.21.4.
        tinted = dict(base, tints=[{"type": "minecraft:dye", "default": LEATHER_DYE_DEFAULT}])
        definition = {"model": {
            "type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
            "entries": [{"threshold": cmd, "model": drawn(m)}
                        for cmd, m, band in bindings if band == 'WISH'],
            "fallback": tinted}}
    else:
        definition = {"model": base}
    (MOD_ITEMS / (item_id + '.json')).write_text(json.dumps(definition, indent=2) + "\n")
print("mod item definitions:", len(registered_item_ids()))
