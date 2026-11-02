package dev.remold.platform;

import com.google.gson.JsonObject;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public interface RemoldPlatform {
    String loaderName();

    boolean isModLoaded(String modId);

    String modName(String namespace);

    Path configDir();

    void sendToServer(CustomPacketPayload payload);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    JsonObject disabledRecipeJson();

    net.minecraft.world.inventory.MenuType<?> unregisteredMenuType();

    JsonObject tagJson(java.util.List<String> add, java.util.List<String> remove, java.util.List<String> current);
}
