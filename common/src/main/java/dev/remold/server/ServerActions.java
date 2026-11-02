package dev.remold.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import dev.remold.data.ChangeEntry;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import dev.remold.core.RecipeUsage;
import dev.remold.core.TemplateRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Server side handling of everything an operator can do from the editor. */
public final class ServerActions {
    private ServerActions() {}

    public static boolean allowed(ServerPlayer player) {
        return RemoldConfig.get().allowed(player);
    }

    private static boolean check(ServerPlayer player) {
        if (allowed(player)) return true;
        Remold.LOG.warn("{} tried to use Remold without permission", player.getGameProfile().getName());
        notice(player, false, "no_permission", "no_permission");
        return false;
    }

    // ------------------------------------------------------------------ handlers

    public static void handleSave(ServerPlayer player, Payloads.SaveRecipe payload) {
        if (!check(player)) return;
        MinecraftServer server = player.server;
        ResourceLocation id = payload.id();
        JsonElement json;
        try {
            json = JsonParser.parseString(payload.json());
        } catch (Exception e) {
            notice(player, false, "invalid", "invalid_json", e.getMessage());
            return;
        }
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var parsed = Recipe.CODEC.parse(ops, json);
        if (parsed.error().isPresent()) {
            notice(player, false, "invalid", "plain", parsed.error().get().message());
            return;
        }
        RemoldPack pack = RemoldPack.get(server);
        ChangeEntry.Kind kind = pack.change(id).map(c -> c.kind() == ChangeEntry.Kind.ADDED ? c.kind() : ChangeEntry.Kind.REPLACED)
                .orElse(server.getRecipeManager().byKey(id).isPresent() ? ChangeEntry.Kind.REPLACED : ChangeEntry.Kind.ADDED);
        String type = json.isJsonObject() && json.getAsJsonObject().has("type") ? json.getAsJsonObject().get("type").getAsString() : "";
        try {
            pack.beginBatch(player.getGameProfile().getName());
            pack.writeRecipe(id, json, entry(player, id, kind, type));
        } catch (IOException e) {
            Remold.LOG.error("Could not write recipe {}", id, e);
            notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
            return;
        }
        finish(player, payload.apply(), kind == ChangeEntry.Kind.ADDED ? "added" : "replaced", id.toString());
    }

    public static void handleAction(ServerPlayer player, Payloads.RecipeAction payload) {
        if (!check(player)) return;
        MinecraftServer server = player.server;
        RemoldPack pack = RemoldPack.get(server);
        ResourceLocation id = payload.id();
        String name = player.getGameProfile().getName();
        try {
            switch (payload.action()) {
                case SYNC -> sendChanges(player);
                case APPLY -> finish(player, true, "applying_changes");
                case UNDO -> {
                    var result = pack.undo();
                    if (result.isEmpty()) {
                        notice(player, false, "nothing_to_undo", "nothing_to_undo");
                        return;
                    }
                    if (result.get().annotations()) broadcastAnnotations(server);
                    if (result.get().recipes()) {
                        finish(player, payload.apply(), "undone", Integer.toString(result.get().changes()), result.get().author());
                    } else {
                        notice(player, true, "done", "undone", Integer.toString(result.get().changes()), result.get().author());
                        broadcastChanges(server);
                    }
                }
                case REVERT -> {
                    pack.beginBatch(name);
                    if (pack.revert(id)) {
                        finish(player, payload.apply(), "reverted", id.toString());
                    } else {
                        notice(player, false, "nothing_to_revert", "nothing_to_revert", id.toString());
                    }
                }
                case REMOVE -> {
                    pack.beginBatch(name);
                    if (remove(player, pack, id)) {
                        finish(player, payload.apply(), "removed", id.toString());
                    } else {
                        notice(player, false, "unknown_recipe", "unknown_recipe", id.toString());
                    }
                }
                case REMOVE_ALL_FOR_ITEM -> {
                    var item = BuiltInRegistries.ITEM.getOptional(id);
                    if (item.isEmpty()) {
                        notice(player, false, "unknown_item", "plain", id.toString());
                        return;
                    }
                    List<ResourceLocation> ids = new ArrayList<>();
                    for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                        ItemStack result = holder.value().getResultItem(server.registryAccess());
                        if (!result.isEmpty() && result.is(item.get())) ids.add(holder.id());
                    }
                    pack.beginBatch(name);
                    for (ResourceLocation recipeId : ids) remove(player, pack, recipeId);
                    finish(player, payload.apply(), "removed_for_item", Integer.toString(ids.size()), id.toString());
                }
            }
        } catch (IOException e) {
            Remold.LOG.error("Remold action {} on {} failed", payload.action(), id, e);
            notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
        }
    }

    private static boolean remove(ServerPlayer player, RemoldPack pack, ResourceLocation id) throws IOException {
        var existing = pack.change(id);
        if (existing.isPresent() && existing.get().kind() == ChangeEntry.Kind.ADDED) {
            return pack.revert(id); // our own recipe: just delete it
        }
        if (existing.isEmpty() && player.server.getRecipeManager().byKey(id).isEmpty()) {
            return false;
        }
        JsonObject disabled = Services.PLATFORM.disabledRecipeJson();
        String type = player.server.getRecipeManager().byKey(id)
                .map(h -> String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType()))).orElse("");
        pack.writeRecipe(id, disabled, entry(player, id, ChangeEntry.Kind.REMOVED, type));
        return true;
    }

    public static void handleTemplateRequest(ServerPlayer player, Payloads.RequestTemplate payload) {
        if (!check(player)) return;
        MinecraftServer server = player.server;
        var holder = server.getRecipeManager().byKey(payload.id());
        if (holder.isEmpty()) {
            Services.PLATFORM.sendToPlayer(player, new Payloads.TemplateJson(payload.id(), "", "Unknown recipe " + payload.id()));
            return;
        }
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var json = Recipe.CODEC.encodeStart(ops, holder.get().value());
        Services.PLATFORM.sendToPlayer(player, json.error().isPresent()
                ? new Payloads.TemplateJson(payload.id(), "", json.error().get().message())
                : new Payloads.TemplateJson(payload.id(), json.getOrThrow().toString(), ""));
    }

    public static void handleTag(ServerPlayer player, Payloads.SetTag payload) {
        if (!check(player)) return;
        MinecraftServer server = player.server;
        ResourceLocation tag = payload.tag();
        for (String entry : concat(payload.edit().add(), payload.edit().remove())) {
            ResourceLocation id = ResourceLocation.tryParse(entry.startsWith("#") ? entry.substring(1) : entry);
            if (id == null || (!entry.startsWith("#") && !BuiltInRegistries.ITEM.containsKey(id))) {
                notice(player, false, "invalid", "unknown_tag_entry", entry);
                return;
            }
        }
        List<String> current = new ArrayList<>();
        server.registryAccess().registryOrThrow(Registries.ITEM).getTag(TagKey.create(Registries.ITEM, tag))
                .ifPresent(set -> set.forEach(h -> current.add(h.unwrapKey().map(k -> k.location().toString()).orElse(""))));
        try {
            RemoldPack pack = RemoldPack.get(server);
            pack.beginBatch(player.getGameProfile().getName());
            pack.setTagEdit(tag, payload.edit(), current);
        } catch (IOException e) {
            notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
            return;
        }
        finish(player, payload.apply(), "tag_saved", "#" + tag);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    public static void handleBulk(ServerPlayer player, Payloads.Bulk payload) {
        if (!check(player)) return;
        MinecraftServer server = player.server;
        RemoldPack pack = RemoldPack.get(server);
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<RecipeHolder<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
        int count = 0;
        try {
            pack.beginBatch(player.getGameProfile().getName());
            switch (payload.kind()) {
                case REPLACE_ITEM -> {
                    var from = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(payload.a()));
                    var to = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(payload.b()));
                    if (from.isEmpty() || to.isEmpty()) {
                        notice(player, false, "unknown_item", "plain", from.isEmpty() ? payload.a() : payload.b());
                        return;
                    }
                    String fromId = BuiltInRegistries.ITEM.getKey(from.get()).toString();
                    String toId = BuiltInRegistries.ITEM.getKey(to.get()).toString();
                    boolean inputs = payload.scope() != 2, outputs = payload.scope() != 1;
                    ItemStack fromStack = new ItemStack(from.get());
                    int viaTag = 0;
                    for (RecipeHolder<?> holder : recipes) {
                        var encoded = Recipe.CODEC.encodeStart(ops, holder.value()).result();
                        if (encoded.isEmpty() || !encoded.get().isJsonObject()) continue;
                        JsonObject replaced = TemplateRecipe.of(holder.id(), encoded.get().getAsJsonObject())
                                .replaceItem(fromId, toId, inputs, outputs);
                        if (replaced == null || Recipe.CODEC.parse(ops, replaced).error().isPresent()) {
                            if (inputs && RecipeUsage.uses(holder.value(), fromStack)) viaTag++;
                            continue;
                        }
                        ChangeEntry.Kind kind = pack.change(holder.id())
                                .filter(c -> c.kind() == ChangeEntry.Kind.ADDED).map(ChangeEntry::kind).orElse(ChangeEntry.Kind.REPLACED);
                        String type = replaced.has("type") ? replaced.get("type").getAsString() : "";
                        pack.writeRecipe(holder.id(), replaced, entry(player, holder.id(), kind, type));
                        count++;
                    }
                    if (viaTag > 0) {
                        finish(player, payload.apply(), "bulk_replaced_tags", fromId, toId, Integer.toString(count), Integer.toString(viaTag));
                    } else {
                        finish(player, payload.apply(), "bulk_replaced", fromId, toId, Integer.toString(count));
                    }
                }
                case REMOVE_USING_ITEM -> {
                    var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(payload.a()));
                    if (item.isEmpty()) {
                        notice(player, false, "unknown_item", "plain", payload.a());
                        return;
                    }
                    ItemStack stack = new ItemStack(item.get());
                    for (RecipeHolder<?> holder : recipes) {
                        if (RecipeUsage.uses(holder.value(), stack) && remove(player, pack, holder.id())) count++;
                    }
                    finish(player, payload.apply(), "bulk_removed_using", payload.a(), Integer.toString(count));
                }
                case REMOVE_FROM_MOD -> {
                    String mod = payload.a().trim();
                    for (RecipeHolder<?> holder : recipes) {
                        String ns = payload.scope() == 1
                                ? BuiltInRegistries.ITEM.getKey(holder.value().getResultItem(server.registryAccess()).getItem()).getNamespace()
                                : holder.id().getNamespace();
                        if (ns.equals(mod) && remove(player, pack, holder.id())) count++;
                    }
                    finish(player, payload.apply(), "bulk_removed_mod", mod, Integer.toString(count));
                }
            }
        } catch (IOException e) {
            Remold.LOG.error("Bulk action failed", e);
            notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
        }
    }

    public static void handleAnnotation(ServerPlayer player, Payloads.SetAnnotation payload) {
        if (!check(player)) return;
        try {
            RemoldPack pack = RemoldPack.get(player.server);
            pack.beginBatch(player.getGameProfile().getName());
            pack.setAnnotation(payload.item(), payload.annotation());
            broadcastAnnotations(player.server);
            broadcastChanges(player.server);
            notice(player, true, "tooltip_saved", payload.annotation().isEmpty() ? "tooltip_cleared" : "tooltip_updated",
                    payload.item().toString());
        } catch (IOException e) {
            notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ChangeEntry entry(ServerPlayer player, ResourceLocation id, ChangeEntry.Kind kind, String type) {
        return new ChangeEntry(id, kind, type, player.getGameProfile().getName(), System.currentTimeMillis());
    }

    /** Applies (reloads) if asked, keeping the operator informed. {@code what} is a notice key. */
    private static void finish(ServerPlayer player, boolean apply, String what, String... args) {
        MinecraftServer server = player.server;
        RemoldPack pack = RemoldPack.get(server);
        if (!apply) {
            notice(player, true, "saved_pending", what, args);
            broadcastChanges(server);
            return;
        }
        CompletableFuture<Void> future = pack.apply();
        notice(player, true, "applying", what, args);
        future.whenComplete((v, err) -> server.execute(() -> {
            if (err == null) {
                notice(player, true, "reloaded", what, args);
            } else {
                notice(player, false, "reload_failed", "plain", String.valueOf(err.getMessage()));
            }
            broadcastChanges(server);
        }));
    }

    /**
     * Sends a toast. Title and message are keys under {@code remold.notice.} (titles end in {@code .title}),
     * so every player sees them in their own language.
     */
    public static void notice(ServerPlayer player, boolean ok, String title, String message, String... args) {
        Services.PLATFORM.sendToPlayer(player, new Payloads.Notice(ok, "remold.notice." + title + ".title",
                "remold.notice." + message, List.of(args)));
    }

    public static void sendChanges(ServerPlayer player) {
        RemoldPack pack = RemoldPack.get(player.server);
        Services.PLATFORM.sendToPlayer(player, new Payloads.SyncChanges(pack.changes(), pack.pendingApply(), pack.canUndo(), pack.tagEdits()));
    }

    public static void broadcastChanges(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (allowed(p)) sendChanges(p);
        }
    }

    public static void sendAccess(ServerPlayer player) {
        RemoldConfig config = RemoldConfig.get();
        Services.PLATFORM.sendToPlayer(player, new Payloads.Access(config.permissionLevel(), config.listed(player)));
    }

    public static void sendAnnotations(ServerPlayer player) {
        Services.PLATFORM.sendToPlayer(player, new Payloads.SyncAnnotations(RemoldPack.get(player.server).annotations()));
    }

    public static void broadcastAnnotations(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(ServerActions::sendAnnotations);
    }

    /** Call when a player joins. */
    public static void onLogin(ServerPlayer player) {
        sendAccess(player);
        sendAnnotations(player);
        if (allowed(player)) sendChanges(player);
    }
}
