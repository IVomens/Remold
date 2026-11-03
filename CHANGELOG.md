# Changelog

## 0.1.10-beta

The first public beta of Remold: make, change and remove recipes in-game, with a GUI. No scripts.

**Minecraft 1.21.1.** Pick the jar for your loader:
- `remold-neoforge-…jar`: NeoForge 21.1 or newer
- `remold-fabric-…jar`: Fabric Loader 0.16 or newer, plus **Fabric API**

Install it on the **server** (or in singleplayer) to edit recipes. Players also need it on their client to use the editor and see Remold's tooltips and padlocks. Players without it can still join.

> **This is a beta.** Back up your world before using it. Every change can be undone in game, and changes are plain datapack files in `world/datapacks/remold/`.

### Getting started
- Operators get a crafting-table button in their inventory (also `/remold` or a key binding) that opens the **home screen**
- Big picture buttons in plain words: *Make a recipe*, *Find recipes*, *My checklist*, *Edit files*, *Unify items*, *Item tags*, *Tooltips*, *Change many* and *Import a pack*
- Big **Undo last change** and **Apply** buttons

### Making recipes
- Crafting (shaped and shapeless), furnace, blast furnace, smoker, campfire, stonecutter and smithing table
- **Any mod's machine**, with no per-mod code: pick a machine, start from one of its real recipes, and swap the items and values (time, energy, chance, fluids...)
- **+ buttons** add more input or output slots to machines that take a list of items, so you don't have to hunt for a recipe with enough slots
- **Item amounts:** scroll over a machine input to change how many it needs (Shift for 10 at a time)
- **Item tags** in recipes (middle-click a slot to cycle them), and **item data** on outputs: name, lore, glint, rarity and more, with `&` colour codes
- Ghost slots: nothing is taken from your inventory

### Changing what's already there
- **Find recipes:** search everything, then edit, remove or restore any recipe, and see what makes or uses an item
- **Edit files:** open recipes from KubeJS scripts and datapacks and save the change straight back into that file. Only the edited recipe changes; the rest of the script, including comments, is left alone
- **My checklist:** see every item of a mod, tick them off as you give them recipes, and press *Next* to jump to the next one
- **Change many:** replace an item in every recipe, or remove every recipe that uses an item or comes from a mod
- **Unify items:** pick your preferred copper ingot (or any material several mods add) and every recipe uses it
- **Item tag editor**, **item tooltips** (like a red *ITEM UNOBTAINABLE*), hiding items from JEI/EMI/REI, and one-click **Make unobtainable**
- **Recipe locks** behind advancements or stages (`/remold stage grant`), with padlocks in JEI and EMI

### Safety nets
- **Undo** for every change, including big bulk actions
- **Progression checker:** warns about items that would become uncraftable and about duplication loops before you save
- **Conflict warnings** when a new recipe clashes with an existing one, and an unsaved-changes prompt
- **No lock-in:** everything is a plain datapack. Uninstall Remold and your recipes still work

### Sharing
- `/remold export` makes a datapack zip and a matching **KubeJS** script
- **Import a pack** applies one from another server or loader

### Compatibility
- **JEI, EMI and REI:** drag items into the editor, and use "+" on any recipe to load it
- Tested with large modpacks (40,000+ recipes) and **NTM: Continuum** machines
- Permissions in `config/remold/remold.json`; all text is translatable (English included)

### Known limitations
- Only Minecraft 1.21.1
- Recipe locks apply to the crafting table and the inventory crafting grid, not to machines or autocrafters; REI shows no padlock
- Some mods' machine recipes may not convert cleanly. Please report them
- Removing or restoring tens of thousands of recipes at once pauses the server for a few seconds
