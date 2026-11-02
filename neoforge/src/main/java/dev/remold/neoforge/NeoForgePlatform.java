package dev.remold.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.remold.platform.RemoldPlatform;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;

public class NeoForgePlatform implements RemoldPlatform {
    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public String modName(String namespace) {
        return ModList.get().getModContainerById(namespace)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public JsonObject tagJson(java.util.List<String> add, java.util.List<String> remove, java.util.List<String> current) {
        // NeoForge supports removing entries from a tag: {"values": [...], "remove": [...]}
        JsonObject json = new JsonObject();
        JsonArray values = new JsonArray();
        add.forEach(values::add);
        json.add("values", values);
        if (!remove.isEmpty()) {
            JsonArray removed = new JsonArray();
            remove.forEach(removed::add);
            json.add("remove", removed);
        }
        return json;
    }

    @Override
    public JsonObject disabledRecipeJson() {
        // {"neoforge:conditions": [{"type": "neoforge:false"}]}
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "neoforge:false");
        JsonArray conditions = new JsonArray();
        conditions.add(condition);
        JsonObject json = new JsonObject();
        json.add("neoforge:conditions", conditions);
        return json;
    }
}
