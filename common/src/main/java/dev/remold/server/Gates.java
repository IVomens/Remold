package dev.remold.server;

import dev.remold.Remold;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recipe gating: a recipe can require an advancement, or a stage ({@code stage:<name>}) granted with
 * {@code /remold stage grant}. Locked recipes give no result in the crafting table or inventory grid.
 */
public final class Gates {
    public static final String STAGE_PREFIX = "stage:";
    private static final Map<UUID, Set<String>> lastSent = new HashMap<>();
    private static final Set<String> warned = new HashSet<>();

    private Gates() {}

    /** Whether the player has met a requirement. Unknown advancements don't lock anything (with a warning). */
    public static boolean meets(ServerPlayer player, String requirement) {
        if (requirement.startsWith(STAGE_PREFIX)) {
            return RemoldPack.get(player.server).stages(player.getUUID()).contains(requirement.substring(STAGE_PREFIX.length()));
        }
        ResourceLocation id = ResourceLocation.tryParse(requirement);
        AdvancementHolder holder = id == null ? null : player.server.getAdvancements().get(id);
        if (holder == null) {
            if (warned.add(requirement)) Remold.LOG.warn("Recipe lock requires unknown advancement '{}'; ignoring it", requirement);
            return true;
        }
        return player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    public static boolean isLocked(ServerPlayer player, @Nullable RecipeHolder<?> recipe) {
        if (recipe == null) return false;
        String requirement = RemoldPack.get(player.server).gates().get(recipe.id());
        return requirement != null && !meets(player, requirement);
    }

    private static Set<String> unlocked(ServerPlayer player, Map<ResourceLocation, String> gates) {
        Set<String> result = new HashSet<>();
        for (String requirement : new HashSet<>(gates.values())) {
            if (meets(player, requirement)) result.add(requirement);
        }
        return result;
    }

    /** Sends the locks and what this player has unlocked (for padlocks in recipe viewers). */
    public static void sync(ServerPlayer player) {
        Map<ResourceLocation, String> gates = RemoldPack.get(player.server).gates();
        Set<String> unlocked = unlocked(player, gates);
        lastSent.put(player.getUUID(), unlocked);
        Services.PLATFORM.sendToPlayer(player, new Payloads.SyncGates(gates, unlocked));
    }

    public static void syncAll(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(Gates::sync);
    }

    /** Every 2 seconds, tell players about requirements they've just met (advancements, stages). */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 40 != 0) return;
        Map<ResourceLocation, String> gates = RemoldPack.get(server).gates();
        if (gates.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!unlocked(player, gates).equals(lastSent.get(player.getUUID()))) sync(player);
        }
    }

    public static void handleSet(ServerPlayer player, Payloads.SetGates payload) {
        try {
            RemoldPack pack = RemoldPack.get(player.server);
            pack.beginBatch(player.getGameProfile().getName());
            pack.setGates(payload.gates());
        } catch (Exception e) {
            ServerActions.notice(player, false, "failed", "plain", String.valueOf(e.getMessage()));
            return;
        }
        syncAll(player.server);
        ServerActions.broadcastChanges(player.server);
        boolean locking = payload.gates().values().stream().anyMatch(v -> !v.isEmpty());
        ServerActions.notice(player, true, "done", locking ? "gates_locked" : "gates_unlocked", Integer.toString(payload.gates().size()));
    }

    public static void onServerStopped() {
        lastSent.clear();
        warned.clear();
    }
}
