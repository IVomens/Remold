<p align="center"><img src="common/src/main/resources/remold.png" width="128" alt="Remold logo"></p>

# Remold

**Make, change and remove recipes in-game, with a GUI. No scripts.**

Remold replaces hand-written KubeJS / CraftTweaker scripts for the most common pack-making job.
Open your inventory as an operator, click the crafting-table button, drop items into a grid, and
press **Save**. That's it.

*Minecraft 1.21.1 · NeoForge and Fabric · works alongside JEI, EMI and REI*

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
| **JEI / EMI / REI** | Drag items from the item list onto the editor slots, and use the **+** button on any recipe to load it into the editor. Machine icons come from the viewer's catalysts/workstations. With JEI and EMI, **R** / **U** work on editor slots. |
| **One-click unification** | **Recipes... → Unify...** finds materials that several mods add (more than one item in a common tag like `#c:ingots/copper`). Pick the preferred item and Remold makes every recipe produce it, lets recipes accept any variant, hides the others from JEI/EMI/REI with a tooltip, and can convert them in players' inventories. One Undo step; run it again after adding mods. |
| **Recipe locks** | Lock any recipe behind an **advancement** or a **stage** (**Recipes... → Lock...**). Locked recipes give no result in the crafting table or inventory grid until the player unlocks them, and show a padlock in JEI and EMI. Grant stages with `/remold stage grant <player> <stage>`, so quest mods can unlock recipes as a reward. |
| **Item tag editor** | Add items to or remove items from any item tag (or create new ones), e.g. put a modded copper ingot in `#c:ingots/copper` so it works in every mod's recipes. Found under **Recipes... → Tags...** |
| **Bulk changes** | **Recipes... → Bulk...**: replace an item with another in every recipe, remove every recipe that uses an item, or remove every recipe from a mod. Shows what will change first, and the whole action is one Undo step. |
| **Usage view** | Search recipes by what they *make* or what they *use*, or click **Uses** on any recipe. The tooltip screen shows how many recipes use an item before you make it unobtainable. |
| **Progression checker** | Before saving, removing or making something unobtainable, Remold warns about items that would no longer be craftable, and about duplication loops (e.g. 9 ingots → 1 block → 10 ingots). Hold Alt to skip. |
| **Import shared packs** | **Recipes... → Import...** applies a pack made with `/remold export` (from any server, NeoForge or Fabric) from the `remold-imports` folder, as one Undo step. Recipes for mods that aren't installed are skipped. |
| **Conflict warnings** | Saving a recipe that uses the same items as an existing one (which Minecraft would silently ignore) asks you to replace the other recipe, save anyway, or cancel. |
| **Undo** | Every change is recorded. **Undo** in the recipe browser (or `/remold undo`) steps back one action at a time, including bulk ones like "make unobtainable". |
| **Permissions** | `config/remold/remold.json` sets the permission level needed, and can allow specific players who aren't operators. |
| **Translatable** | All text is in `assets/remold/lang/`. Add a file such as `de_de.json` to translate Remold. |
| **Batch changes** | Shift-click Save/Remove to queue changes, then **Apply** once. Reloading a big pack takes time, so this keeps it to one reload. |
| **Plain datapack output** | Everything goes to `world/datapacks/remold/` as standard recipe JSON. Uninstall Remold and your recipes still work. |
| **Export** | `/remold export` writes a zipped datapack plus an equivalent **KubeJS** script (recipes and tags), for modpacks and other servers. |

## Using it

1. Be an operator (permission level 2), or open a singleplayer world with cheats on.
   Server owners can change who may edit in `config/remold/remold.json`:
   ```json
   { "permission_level": 2, "allowed_players": ["Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5"] }
   ```
   Changes take effect on `/remold apply` or a restart.
2. Open your inventory and click the small **crafting table** button in the top-right.
   (Or run `/remold`, or bind *Open recipe editor* in Controls.)
3. Click **the machine bar** to choose a crafting table, a furnace, or any modded machine.
4. Put items in the slots:
   - click an item in your inventory to pick up a ghost copy, then click slots (hold to paint);
   - or **Items...** to search every item (`@modid` filters by mod; right-click to favourite, and
     favourites and recent items come first);
   - or drag from JEI / EMI / REI.
   Right-click clears a slot. Scroll over the output to change its count.
5. **Save** (Ctrl+S). The recipe is live straight away.

| Command | |
|---|---|
| `/remold` | open the editor |
| `/remold apply` | apply queued changes (same as `/reload`) |
| `/remold undo` | undo the most recent change |
| `/remold stage grant\|revoke <players> <stage>` | unlock or re-lock recipes locked behind that stage |
| `/remold stage list <player>` | show a player's stages |
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
compat-rei/ REI plugin. REI's API differs per loader, so each loader module compiles it.
neoforge/   ~150 lines: events, payload registration, RemoldPlatform for NeoForge
fabric/     ~200 lines: the same for Fabric API, plus one mixin accessor for the inventory position
```

* **Recipes** are built as real recipe objects and encoded with the game's own codec, then
  validated again on the server before anything is written.
* **Modded machines**: every 1.21 recipe can be encoded to JSON with its own codec. Remold asks the
  server for an existing recipe of the machine, scans the JSON for items, tags and stacks (those
  become slots) and for numbers, strings and booleans (those become editable values). Then it writes
  an edited copy back through the same codec. The target mod validates the result itself, so
  any data-driven machine works.
* **Tags** are written to `data/<namespace>/tags/item/` in the same pack. On NeoForge, removals use
  NeoForge's `remove` list. Fabric has no tag removal, so a tag with removals is rewritten in full.
* **Removing** writes a recipe file that the loader skips (`neoforge:conditions: [neoforge:false]`)
  over the original id, in a datapack with the highest priority.

## Porting

**Another loader:** implement `RemoldPlatform` (packets, config dir, mod names, and the JSON that
makes the loader skip a recipe) and forward the same events as `neoforge/` and `fabric/` do:
commands, player join, server start/stop, screen init (inventory button), item tooltips and the key binding.

**Other Minecraft versions:** the version-sensitive code is in `core/RecipeFactory` (recipe
constructors), `client/screen` (GUI API), and `Payloads`. From 1.21.2 the client no longer receives
the full recipe list, so the browser and machine list will need to fetch them from the server (the
same way templates already are). 1.20.1 has no data components, so item data would use NBT instead.

## Building

```
./gradlew build                    # jars in neoforge/build/libs and fabric/build/libs
./gradlew :neoforge:runClient      # NeoForge dev client with JEI
./gradlew :fabric:runClient        # Fabric dev client with REI
```

### Nightly builds

`.github/workflows/nightly.yml` builds both jars every night at 03:17 UTC and publishes them as the
rolling **nightly** pre-release on the Releases page, versioned like `0.1.0-nightly.20261102.abc1234`.
Nights without new commits are skipped. Run it any time from the Actions tab with **Run workflow**.

## Roadmap ideas

- Fluid slots for machine templates (fluids are editable as text values today)
- Edit tags in-game (add items to `#c:ingots/...`)
- Undo history and per-change diff view
- Shared "recipe packs": import a Remold export directly from the GUI

## License

Copyright (c) 2026 IVomens. **All rights reserved.** See [LICENSE](LICENSE).
