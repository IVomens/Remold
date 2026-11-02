# Changelog

## 0.1.0-beta.1

The first public beta of Remold: make, change and remove recipes in-game, with a GUI. No scripts.

**Minecraft 1.21.1.** Pick the jar for your loader:
- `remold-neoforge-…jar`: NeoForge 21.1 or newer
- `remold-fabric-…jar`: Fabric Loader 0.16 or newer, plus **Fabric API**

Install it on the **server** (or in singleplayer) to edit recipes. Players also need it on their client to use the editor and see Remold's tooltips and padlocks. Players without it can still join.

> **This is a beta.** Back up your world before using it. Every change can be undone in game, and changes are plain datapack files in `world/datapacks/remold/`.

### Features
- **Recipe editor** for operators: a button in the inventory, `/remold`, or a key binding
- Crafting (shaped and shapeless), furnace, blast furnace, smoker, campfire, stonecutter, smithing table, and **any mod's machine** (edits a copy of one of that machine's recipes)
- **Item tags** in recipes, and **item data** on outputs: name, lore, glint, rarity and more, with `&` colour codes
- **Recipe browser:** edit, remove or restore any recipe, and see what makes or uses an item
- **Item tooltips** everywhere, hiding items from JEI/EMI/REI, and one-click **Make unobtainable**
- **Item tag editor**, **bulk changes** (replace an item everywhere, remove recipes by item or mod) and **one-click unification** of duplicate materials from different mods
- **Recipe locks** behind advancements or stages (`/remold stage grant`), with padlocks in JEI and EMI
- **Progression checker:** warns about items that would become uncraftable and about duplication loops
- **Conflict warnings, undo, unsaved-changes prompt**
- **Export** (datapack zip and KubeJS script) and **import** of shared packs
- Works with **JEI, EMI and REI**: drag items into the editor, and use "+" on any recipe to load it
- Permissions in `config/remold/remold.json`; all text is translatable (English included)

### Known limitations
- Only Minecraft 1.21.1
- Recipe locks apply to the crafting table and the inventory crafting grid, not to machines or autocrafters; REI shows no padlock
- Editing other mods' machines works from a copy of an existing recipe; some mods' recipes may not convert cleanly. Please report them
- Large modpacks may notice a short pause when saving, while Remold checks for conflicts and broken progression (hold Alt to skip the checks)
