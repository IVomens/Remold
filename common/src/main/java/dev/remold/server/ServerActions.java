package dev.remold.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import dev.remold.data.ChangeEntry;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
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
        return player.hasPermissions(Remold.PERMISSION_LEVEL);
    }

    private static boolean check(ServerPlayer player) {
        if (allowed(player)) return true;
        Remold.LOG.warn("{} tried to use Remold without permission", player.getGameProfile().getName());
        notice(player, false, "No permission", "You need to be an operator to edit recipes.");
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
            notice(player, false, "Invalid recipe", "Malformed JSON: " + e.getMessage());
            return;
        }
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var parsed = Recipe.CODEC.parse(ops, json);
        if (parsed.error().isPresent()) {
            notice(player, false, "Invalid recipe", parsed.error().get().message());
            return;
        }
        RemoldPack pack = RemoldPack.get(server);
        ChangeEntry.Kind kind = pack.change(id).map(c -> c.kind() == ChangeEntry.Kind.ADDED ? c.kind() : ChangeEntry.Kind.REPLACED)
                .orElse(server.getRecipeManager().byKey(id).isPresent() ? ChangeEntry.Kind.REPLACED : ChangeEntry.Kind.ADDED);
        String type = json.isJsonObject() && json.getAsJsonObject().has("type") ? json.getAsJsonObject().get("type").getAsString() : "";
        try {
            pack.writeRecipe(id, json, entry(player, id, kind, type));
        } catch (IOException e) {
            Remold.LOG.error("Could not write recipe {}", id, e);
            notice(player, false, "Save failed", e.getMessage());
            return;
        }
        finish(player, payload.apply(), (kind == ChangeEntry.Kind.ADDED ? "Added " : "Replaced ") + id);
    }

    public static void handleAction(ServerPlayer player, Payloads.RecipeAction payload) {
        if (!check(player)) return;
        MinecraftServer server = player.server;
        RemoldPack pack = RemoldPack.get(server);
        ResourceLocation id = payload.id();
        try {
            switch (payload.action()) {
                case SYNC -> sendChanges(player);
                case APPLY -> finish(player, true, "Applying changes");
                case REVERT -> {
                    if (pack.revert(id)) {
                        finish(player, payload.apply(), "Reverted " + id);
                    } else {
                        notice(player, false, "Nothing to revert", id + " was not changed by Remold");
                    }
                }
                case REMOVE -> {
                    if (remove(player, pack, id)) {
                        finish(player, payload.apply(), "Removed " + id);
                    } else {
                        notice(player, false, "Unknown recipe", "There is no recipe " + id);
                    }
                }
                case REMOVE_ALL_FOR_ITEM -> {
                    var item = BuiltInRegistries.ITEM.getOptional(id);
                    if (item.isEmpty()) {
                        notice(player, false, "Unknown item", id.toString());
                        return;
                    }
                    List<ResourceLocation> ids = new ArrayList<>();
                    for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                        ItemStack result = holder.value().getResultItem(server.registryAccess());
                        if (!result.isEmpty() && result.is(item.get())) ids.add(holder.id());
                    }
                    for (ResourceLocation recipeId : ids) remove(player, pack, recipeId);
                    finish(player, payload.apply(), "Removed " + ids.size() + " recipe(s) for " + id);
                }
            }
        } catch (IOException e) {
            Remold.LOG.error("Remold action {} on {} failed", payload.action(), id, e);
            notice(player, false, "Failed", e.getMessage());
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

    public static void handleAnnotation(ServerPlayer player, Payloads.SetAnnotation payload) {
        if (!check(player)) return;
        try {
            RemoldPack pack = RemoldPack.get(player.server);
            pack.setAnnotation(payload.item(), payload.annotation());
            broadcastAnnotations(player.server);
            notice(player, true, "Tooltip saved", payload.annotation().isEmpty()
                    ? "Cleared " + payload.item() : "Updated " + payload.item());
        } catch (IOException e) {
            notice(player, false, "Failed", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ChangeEntry entry(ServerPlayer player, ResourceLocation id, ChangeEntry.Kind kind, String type) {
        return new ChangeEntry(id, kind, type, player.getGameProfile().getName(), System.currentTimeMillis());
    }

    private static void finish(ServerPlayer player, boolean apply, String what) {
        MinecraftServer server = player.server;
        RemoldPack pack = RemoldPack.get(server);
        if (!apply) {
            notice(player, true, "Saved (not applied yet)", what + ". Use Apply or /reload when ready.");
            broadcastChanges(server);
            return;
        }
        CompletableFuture<Void> future = pack.apply();
        notice(player, true, "Applying...", what);
        future.whenComplete((v, err) -> server.execute(() -> {
            if (err == null) {
                notice(player, true, "Recipes reloaded", what);
            } else {
                notice(player, false, "Reload failed", String.valueOf(err.getMessage()));
            }
            broadcastChanges(server);
        }));
    }

    public static void notice(ServerPlayer player, boolean ok, String title, String message) {
        Services.PLATFORM.sendToPlayer(player, new Payloads.Notice(ok, title, message));
    }

    public static void sendChanges(ServerPlayer player) {
        RemoldPack pack = RemoldPack.get(player.server);
        Services.PLATFORM.sendToPlayer(player, new Payloads.SyncChanges(pack.changes(), pack.pendingApply()));
    }

    public static void broadcastChanges(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (allowed(p)) sendChanges(p);
        }
    }

    public static void sendAnnotations(ServerPlayer player) {
        Services.PLATFORM.sendToPlayer(player, new Payloads.SyncAnnotations(RemoldPack.get(player.server).annotations()));
    }

    public static void broadcastAnnotations(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(ServerActions::sendAnnotations);
    }

    /** Call when a player joins. */
    public static void onLogin(ServerPlayer player) {
        sendAnnotations(player);
        if (allowed(player)) sendChanges(player);
    }
}
