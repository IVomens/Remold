package dev.remold.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.remold.client.ClientState;
import dev.remold.client.RemoldClient;
import dev.remold.fabric.mixin.AbstractContainerScreenAccessor;
import dev.remold.network.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

public class RemoldFabricClient implements ClientModInitializer {
    private static final KeyMapping OPEN_EDITOR = new KeyMapping("key.remold.open_editor", InputConstants.UNKNOWN.getValue(), "key.categories.remold");

    @Override
    public void onInitializeClient() {
        toClient(Payloads.OpenEditor.TYPE, RemoldClient::handle);
        toClient(Payloads.SyncAnnotations.TYPE, RemoldClient::handle);
        toClient(Payloads.SyncChanges.TYPE, RemoldClient::handle);
        toClient(Payloads.TemplateJson.TYPE, RemoldClient::handle);
        toClient(Payloads.Access.TYPE, RemoldClient::handle);
        toClient(Payloads.ImportList.TYPE, RemoldClient::handle);
        toClient(Payloads.SyncGates.TYPE, RemoldClient::handle);
        toClient(Payloads.Notice.TYPE, RemoldClient::handle);

        KeyBindingHelper.registerKeyBinding(OPEN_EDITOR);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_EDITOR.consumeClick()) {
                RemoldClient.openEditor(client.screen);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?> container) {
                AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) container;
                var button = RemoldClient.inventoryButton(screen, accessor::remold$getLeftPos, accessor::remold$getTopPos);
                if (button != null) Screens.getButtons(screen).add(button);
            }
        });
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> RemoldClient.appendTooltip(stack, lines));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());
    }

    private static <T extends CustomPacketPayload> void toClient(CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                Minecraft.getInstance().execute(() -> handler.accept(payload)));
    }
}
