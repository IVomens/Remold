package dev.remold.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.remold.client.ClientState;
import dev.remold.client.RemoldClient;
import dev.remold.client.screen.EditorScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

final class RemoldNeoForgeClient {
    static final KeyMapping OPEN_EDITOR = new KeyMapping("key.remold.open_editor", InputConstants.UNKNOWN.getValue(), "key.categories.remold");

    private RemoldNeoForgeClient() {}

    static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN_EDITOR));

        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) -> {
            if (e.getScreen() instanceof AbstractContainerScreen<?> screen) {
                var button = RemoldClient.inventoryButton(screen, screen::getGuiLeft, screen::getGuiTop);
                if (button != null) e.addListener(button);
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ScreenEvent.MouseScrolled.Pre e) -> {
            if (e.getScreen() instanceof EditorScreen editor && editor.scrollAmount(e.getMouseX(), e.getMouseY(), e.getScrollDeltaY())) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ItemTooltipEvent e) -> RemoldClient.appendTooltip(e.getItemStack(), e.getToolTip()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            while (OPEN_EDITOR.consumeClick()) {
                RemoldClient.openEditor(Minecraft.getInstance().screen);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> ClientState.reset());
    }
}
