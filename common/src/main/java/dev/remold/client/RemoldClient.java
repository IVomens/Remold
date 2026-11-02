package dev.remold.client;

import dev.remold.Remold;
import dev.remold.client.screen.EditorMenu;
import dev.remold.client.screen.EditorScreen;
import dev.remold.client.widget.IconButton;
import dev.remold.data.Annotation;
import dev.remold.data.TextUtil;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.IntSupplier;

/** Client entry points. Loader modules forward their events here. */
public final class RemoldClient {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);

    private RemoldClient() {}

    public static boolean canEdit() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(Remold.PERMISSION_LEVEL);
    }

    public static void openEditor(@Nullable Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!canEdit()) {
            toast(false, "Remold", "Only operators can edit recipes.");
            return;
        }
        Services.PLATFORM.sendToServer(Payloads.RecipeAction.simple(Payloads.Action.SYNC));
        EditorScreen screen = new EditorScreen(new EditorMenu(mc.player.getInventory(), EditorScreen.INV_X, EditorScreen.INV_Y), mc.player.getInventory(), parent);
        mc.setScreen(screen);
    }

    // ------------------------------------------------------------------ payloads

    public static void handle(Payloads.OpenEditor payload) {
        openEditor(null);
    }

    public static void handle(Payloads.SyncAnnotations payload) {
        ClientState.setAnnotations(payload.annotations());
    }

    public static void handle(Payloads.SyncChanges payload) {
        ClientState.setChanges(payload.changes(), payload.pendingApply());
    }

    public static void handle(Payloads.Notice payload) {
        toast(payload.success(), payload.title(), payload.message());
    }

    public static void toast(boolean success, String title, String message) {
        Minecraft mc = Minecraft.getInstance();
        Component t = Component.literal(title).withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED);
        mc.getToasts().addToast(SystemToast.multiline(mc, TOAST, t, Component.literal(message)));
    }

    // ------------------------------------------------------------------ tooltips

    /** Adds operator-defined tooltip lines right below the item name. */
    public static void appendTooltip(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty()) return;
        Annotation annotation = ClientState.annotations().get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (annotation == null || annotation.lines().isEmpty()) return;
        int insertAt = Math.min(1, lines.size());
        for (String line : annotation.lines()) {
            lines.add(insertAt++, TextUtil.parse(line));
        }
    }

    // ------------------------------------------------------------------ inventory button

    /**
     * Creates the small crafting-table button shown in the inventory of operators. Its position is
     * recomputed every frame because the inventory moves when the recipe book is toggled.
     *
     * @return the widget, or null if this screen gets no button
     */
    @Nullable
    public static AbstractWidget inventoryButton(Screen screen, IntSupplier guiLeft, IntSupplier guiTop) {
        int dx, dy, size;
        if (screen instanceof InventoryScreen) {
            dx = 154;
            dy = 6;
            size = 16;
        } else if (screen instanceof CreativeModeInventoryScreen) {
            dx = 176;
            dy = 3;
            size = 12;
        } else {
            return null;
        }
        ItemStack icon = new ItemStack(Items.CRAFTING_TABLE);
        Tooltip tooltip = Tooltip.create(Component.literal("Remold: recipe editor").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\nCreate, change or remove recipes").withStyle(ChatFormatting.GRAY)));
        IconButton button = new IconButton(guiLeft.getAsInt() + dx, guiTop.getAsInt() + dy, size, size,
                Component.literal("Remold"), () -> icon, true, b -> openEditor(screen)) {
            @Override
            public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                setX(guiLeft.getAsInt() + dx);
                setY(guiTop.getAsInt() + dy);
                boolean show = canEdit()
                        && !(screen instanceof CreativeModeInventoryScreen creative && !creative.isInventoryOpen());
                // Hidden buttons stay in the widget list but are inert, because visibility can change
                // while the screen is open (e.g. switching creative tabs, being de-opped).
                active = show;
                setTooltip(show ? tooltip : null);
                if (show) super.renderWidget(g, mouseX, mouseY, partialTick);
            }
        };
        button.setTooltip(tooltip);
        return button;
    }
}
