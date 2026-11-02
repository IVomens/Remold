# Remold

**Make, change and remove recipes in-game, with a GUI. No scripts.**

Remold replaces hand-written KubeJS / CraftTweaker scripts for the most common pack-making job.
Open your inventory as an operator, click the crafting-table button, drop items into a grid, and
press **Save**. That's it.

*Minecraft 1.21.1 · NeoForge (Fabric-ready architecture) · works alongside JEI and EMI*

---

## Features

| | |
|---|---|
| **In-game recipe editor** | An inventory button (operators only) opens a crafting GUI with "ghost" slots: nothing is taken from your inventory. |
| **Every vanilla station** | Shaped and shapeless crafting, furnace, blast furnace, smoker, campfire, stonecutter, smithing table. |
| **Any modded machine** | Pick any recipe type from any installed mod. Remold starts from one of that machine's real recipes and lets you swap the items and change the values (time, energy, chance, fluids...). No per-mod code needed. |
| **Edit or remove existing recipes** | Search all recipes, then **Edit** (save with the same id to replace it), **Remove**, or **Restore** the original. |
| **Item tags** | Middle-click (or Ctrl-click) an input to cycle through its tags, e.g. *any* `#minecraft:planks`. |
| **Item data without JSON** | Give the output a custom name, lore lines, glint, rarity, unbreakable, model data and count, using `&` colour codes. |
| **Item tooltips everywhere** | Add lines like a red **ITEM UNOBTAINABLE** to an item for every player, and optionally hide it from JEI/EMI. |
| **Make unobtainable** | One click removes every recipe (from every mod and machine) that makes an item, and tags it. |
| **JEI / EMI** | Drag items from the item list onto the editor slots. The JEI **+** button on any recipe loads it into the editor. **R** / **U** work on editor slots. Machine icons come from the viewer's catalysts/workstations. |
| **Batch changes** | Shift-click Save/Remove to queue changes, then **Apply** once. Reloading a big pack takes time, so this keeps it to one reload. |
| **Plain datapack output** | Everything goes to `world/datapacks/remold/` as standard recipe JSON. Uninstall Remold and your recipes still work. |
| **Export** | `/remold export` writes a zipped datapack plus an equivalent **KubeJS** script, for modpacks and other servers. |

## Using it

1. Be an operator (permission level 2), or open a singleplayer world with cheats on.
2. Open your inventory and click the small **crafting table** button in the top-right.
   (Or run `/remold`, or bind *Open recipe editor* in Controls.)
3. Click **the machine bar** to choose a crafting table, a furnace, or any modded machine.
4. Put items in the slots:
   - click an item in your inventory to pick up a ghost copy, then click slots (hold to paint);
   - or **Items...** to search every item (`@modid` filters by mod);
   - or drag from JEI / EMI.
   Right-click clears a slot. Scroll over the output to change its count.
5. **Save** (Ctrl+S). The recipe is live straight away.

| Command | |
|---|---|
| `/remold` | open the editor |
| `/remold apply` | apply queued changes (same as `/reload`) |
| `/remold list` | list every change, with author |
| `/remold export` | export to `remold-exports/` (datapack zip + KubeJS script) |

Modpack authors can ship default tooltips in `config/remold/annotations.json` (same format as
`world/datapacks/remold/remold_annotations.json`).

## How it works

```
common/     loader-agnostic code. Compiles against vanilla only, so it can't depend on a loader.
  core/       editor state, recipe building, the generic machine "template" engine
  server/     the world datapack, permission checks, commands, export
  client/     screens and widgets (no textures; everything is drawn)
  compat/     JEI and EMI plugins (optional, loaded only if the viewer is installed)
  network/    payloads (vanilla CustomPacketPayload + StreamCodec)
  platform/   RemoldPlatform: the only thing a loader has to implement
neoforge/   ~150 lines: events, payload registration, RemoldPlatform for NeoForge
```

* **Recipes** are built as real recipe objects and encoded with the game's own codec, then
  validated again on the server before anything is written.
* **Modded machines**: every 1.21 recipe can be encoded to JSON with its own codec. Remold asks the
  server for an existing recipe of the machine, scans the JSON for items, tags and stacks (those
  become slots) and for numbers, strings and booleans (those become editable values). Then it writes
  an edited copy back through the same codec. The target mod validates the result itself, so
  any data-driven machine works.
* **Removing** writes a recipe file that the loader skips (`neoforge:conditions: [neoforge:false]`)
  over the original id, in a datapack with the highest priority.

## Porting

**Fabric:** add a `fabric/` module next to `neoforge/` that
1. implements `RemoldPlatform` (send packets, config dir, mod names,
   `disabledRecipeJson` → `{"fabric:load_conditions":[{"condition":"fabric:not","value":{"condition":"fabric:true"}}]}`);
2. registers the payloads in `Payloads` with `PayloadTypeRegistry` and forwards them to `ServerActions` / `RemoldClient`;
3. forwards the same events `RemoldNeoForge` / `RemoldNeoForgeClient` do: commands, player join, server stop,
   screen init (inventory button; needs an accessor for `leftPos`/`topPos`), item tooltip, key binding;
4. adds the `jei_mod_plugin` and `emi` entrypoints for the classes in `compat/`.

**Other Minecraft versions:** the version-sensitive code is in `core/RecipeFactory` (recipe
constructors), `client/screen` (GUI API), and `Payloads`. From 1.21.2 the client no longer receives
the full recipe list, so the browser and machine list will need to fetch them from the server (the
same way templates already are). 1.20.1 has no data components, so item data would use NBT instead.

## Building

```
./gradlew build                    # jar in neoforge/build/libs
./gradlew :neoforge:runClient      # dev client with JEI
```

## Roadmap ideas

- Fabric module and REI drag-and-drop
- Fluid slots for machine templates (fluids are editable as text values today)
- Edit tags in-game (add items to `#c:ingots/...`)
- Undo history and per-change diff view
- Shared "recipe packs": import a Remold export directly from the GUI

## License

Copyright (c) 2026 IVomens. **All rights reserved.** See [LICENSE](LICENSE).
