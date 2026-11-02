package dev.remold.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import dev.remold.core.TemplateRecipe;
import dev.remold.data.Annotation;
import dev.remold.data.ChangeEntry;
import dev.remold.data.UnifySettings;
import dev.remold.network.Payloads;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-click item unification: when several mods add the same material (five copper ingots, all in
 * {@code #c:ingots/copper}), make every recipe produce one preferred item, let recipes accept any of
 * them, hide the others from recipe viewers and optionally convert them in inventories.
 */
public final class Unifier {
    private Unifier() {}

    /** Other item → preferred item, for inventory conversion. Rebuilt after reloads and changes. */
    private static Map<Item, Item> conversions;
    private static MinecraftServer conversionsFor;

    private static List<Item> members(MinecraftServer server, ResourceLocation tag) {
        List<Item> items = new ArrayList<>();
        server.registryAccess().registryOrThrow(Registries.ITEM).getTag(TagKey.create(Registries.ITEM, tag))
                .ifPresent(set -> set.forEach(h -> items.add(h.value())));
        return items;
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static void apply(ServerPlayer player, Payloads.Unify payload) {
        MinecraftServer server = player.server;
        RemoldPack pack = RemoldPack.get(server);
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        UnifySettings settings = payload.settings();

        // Settings for groups not sent this time are kept, so unification can be done a few groups at a time.
        Map<ResourceLocation, ResourceLocation> groups = new LinkedHashMap<>(pack.unify().groups());
        groups.putAll(settings.groups());
        UnifySettings stored = new UnifySettings(groups, settings.hide(), settings.convert(), settings.tagInputs());

        int recipes = 0;
        try {
            pack.beginBatch(player.getGameProfile().getName());
            // Work on the current JSON of each recipe, so several groups can change the same recipe.
            Map<ResourceLocation, JsonObject> changed = new LinkedHashMap<>();
            Map<ResourceLocation, JsonObject> current = new HashMap<>();
            for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
                Recipe.CODEC.encodeStart(ops, h.value()).result().filter(JsonElement::isJsonObject)
                        .ifPresent(json -> current.put(h.id(), json.getAsJsonObject()));
            }
            for (var group : settings.groups().entrySet()) {
                String tag = group.getKey().toString();
                String preferred = group.getValue().toString();
                Set<String> all = new HashSet<>();
                for (Item item : members(server, group.getKey())) all.add(id(item));
                if (!all.contains(preferred)) continue;
                for (var entry : current.entrySet()) {
                    JsonObject json = entry.getValue();
                    boolean touched = false;
                    for (String other : all) {
                        if (other.equals(preferred)) continue;
                        JsonObject next = TemplateRecipe.of(entry.getKey(), json).replaceItem(other, preferred, false, true);
                        if (next != null) {
                            json = next;
                            touched = true;
                        }
                    }
                    if (settings.tagInputs()) {
                        JsonObject next = TemplateRecipe.of(entry.getKey(), json).widenToTag(all, tag);
                        if (next != null) {
                            json = next;
                            touched = true;
                        }
                    }
                    if (touched) {
                        entry.setValue(json);
                        changed.put(entry.getKey(), json);
                    }
                }
            }
            for (var e : changed.entrySet()) {
                if (Recipe.CODEC.parse(ops, e.getValue()).error().isPresent()) continue;
                ResourceLocation id = e.getKey();
                ChangeEntry.Kind kind = pack.change(id).filter(c -> c.kind() == ChangeEntry.Kind.ADDED)
                        .map(ChangeEntry::kind).orElse(ChangeEntry.Kind.REPLACED);
                String type = e.getValue().has("type") ? e.getValue().get("type").getAsString() : "";
                pack.writeRecipe(id, e.getValue(), ServerActions.entry(player, id, kind, type));
                recipes++;
            }
            if (settings.hide()) {
                for (var group : settings.groups().entrySet()) {
                    Item preferred = BuiltInRegistries.ITEM.get(group.getValue());
                    String line = "&8Unified into " + preferred.getDescription().getString();
                    for (Item other : members(server, group.getKey())) {
                        if (other == preferred) continue;
                        ResourceLocation otherId = BuiltInRegistries.ITEM.getKey(other);
                        Annotation existing = pack.annotations().getOrDefault(otherId, Annotation.EMPTY);
                        List<String> lines = new ArrayList<>(existing.lines());
                        if (!lines.contains(line)) lines.add(line);
                        pack.setAnnotation(otherId, new Annotation(lines, true));
                    }
                }
            }
            pack.setUnify(stored);
        } catch (IOException e) {
            Remold.LOG.error("Unification failed", e);
            ServerActions.notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
            return;
        }
        conversions = null;
        ServerActions.broadcastAnnotations(server);
        ServerActions.finish(player, payload.apply(), "unified", Integer.toString(settings.groups().size()), Integer.toString(recipes));
    }

    /** Clears the conversion cache (after reloads, undo, ...). */
    public static void invalidate() {
        conversions = null;
    }

    private static Map<Item, Item> conversions(MinecraftServer server) {
        if (conversions == null || conversionsFor != server) {
            Map<Item, Item> map = new HashMap<>();
            UnifySettings settings = RemoldPack.get(server).unify();
            if (settings.convert()) {
                for (var group : settings.groups().entrySet()) {
                    Item preferred = BuiltInRegistries.ITEM.get(group.getValue());
                    for (Item other : members(server, group.getKey())) {
                        if (other != preferred) map.put(other, preferred);
                    }
                }
            }
            conversions = map;
            conversionsFor = server;
        }
        return conversions;
    }

    /** Call every server tick. Every second, swaps unified-away items in players' inventories. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        Map<Item, Item> map = conversions(server);
        if (map.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                Item target = stack.isEmpty() ? null : map.get(stack.getItem());
                if (target == null) continue;
                ItemStack converted = new ItemStack(Holder.direct(target), stack.getCount(), stack.getComponentsPatch());
                inv.setItem(i, converted);
            }
        }
    }
}
