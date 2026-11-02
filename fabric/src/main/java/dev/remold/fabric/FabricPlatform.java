package dev.remold.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.remold.platform.RemoldPlatform;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public class FabricPlatform implements RemoldPlatform {
    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public String modName(String namespace) {
        return FabricLoader.getInstance().getModContainer(namespace)
                .map(c -> c.getMetadata().getName())
                .orElse(namespace);
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // Servers without Remold don't know the channel; the editor simply won't respond there.
        if (ClientPlayNetworking.canSend(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    @Override
    public JsonObject tagJson(java.util.List<String> add, java.util.List<String> remove, java.util.List<String> current) {
        JsonObject json = new JsonObject();
        JsonArray values = new JsonArray();
        if (remove.isEmpty()) {
            add.forEach(values::add);
        } else {
            // Vanilla/Fabric tags can't remove entries, so replace the tag with its current contents
            // minus the removed items. Removed #tags are expanded already, so only items are filtered.
            json.addProperty("replace", true);
            java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>(current);
            entries.addAll(add);
            entries.removeAll(remove);
            entries.forEach(values::add);
        }
        json.add("values", values);
        return json;
    }

    @Override
    public net.minecraft.world.inventory.MenuType<?> unregisteredMenuType() {
        // Fabric API's access widener makes this constructor public.
        return new net.minecraft.world.inventory.MenuType<>((id, inventory) -> null, net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
    }

    @Override
    public JsonObject disabledRecipeJson() {
        // {"fabric:load_conditions": [{"condition": "fabric:not", "value": {"condition": "fabric:true"}}]}
        JsonObject alwaysTrue = new JsonObject();
        alwaysTrue.addProperty("condition", "fabric:true");
        JsonObject not = new JsonObject();
        not.addProperty("condition", "fabric:not");
        not.add("value", alwaysTrue);
        JsonArray conditions = new JsonArray();
        conditions.add(not);
        JsonObject json = new JsonObject();
        json.add("fabric:load_conditions", conditions);
        return json;
    }
}
