package dev.remold.client;

import dev.remold.RemoldText;
import dev.remold.Remold;
import dev.remold.client.screen.EditorMenu;
import dev.remold.client.screen.EditorScreen;
import dev.remold.client.widget.IconButton;
import dev.remold.core.TemplateCache;
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
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.IntSupplier;

public final class RemoldClient {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);

    private RemoldClient() {}

    public static boolean canEdit() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && ClientState.canEdit(mc.player);
    }

    public static void openEditor(@Nullable Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!canEdit()) {
            toast(false, RemoldText.tr("name"), RemoldText.tr("notice.no_permission"));
            return;
        }
        Services.PLATFORM.sendToServer(Payloads.RecipeAction.simple(Payloads.Action.SYNC));
        EditorScreen screen = new EditorScreen(new EditorMenu(mc.player.getInventory(), EditorScreen.INV_X, EditorScreen.INV_Y), mc.player.getInventory(), parent);
        mc.setScreen(screen);
    }

    public static void handle(Payloads.OpenEditor payload) {
        openEditor(null);
    }

    public static void handle(Payloads.SyncAnnotations payload) {
        ClientState.setAnnotations(payload.annotations());
    }

    public static void handle(Payloads.SyncChanges payload) {
        ClientState.setChanges(payload.changes(), payload.pendingApply(), payload.canUndo(), payload.tagEdits(), payload.unify());
        TemplateCache.clear();
    }

    public static void handle(Payloads.TemplateJson payload) {
        TemplateCache.receive(payload);
        if (Minecraft.getInstance().screen instanceof EditorScreen editor) {
            editor.onTemplateReceived(payload.id());
        }
    }

    public static void handle(Payloads.SyncGates payload) {
        ClientState.setGates(payload.gates(), payload.unlocked());
    }

    public static List<Component> lockTooltip(ResourceLocation recipe) {
        String requirement = ClientState.gate(recipe);
        if (requirement == null) return List.of();
        boolean met = ClientState.isUnlocked(requirement);
        return List.of(dev.remold.RemoldText.tr(met ? "gate.unlocked" : "gate.locked").withStyle(met ? ChatFormatting.GREEN : ChatFormatting.RED),
                dev.remold.RemoldText.tr("gate.requires", describe(requirement)).withStyle(ChatFormatting.GRAY));
    }

    public static Component describe(String requirement) {
        if (requirement.startsWith(dev.remold.server.Gates.STAGE_PREFIX)) {
            return dev.remold.RemoldText.tr("gate.stage", requirement.substring(dev.remold.server.Gates.STAGE_PREFIX.length()));
        }
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(requirement);
        if (id != null && mc.player != null) {
            var holder = mc.player.connection.getAdvancements().get(id);
            if (holder != null && holder.value().display().isPresent()) {
                return holder.value().display().get().getTitle().copy();
            }
        }
        return Component.literal(requirement);
    }

    public static void handle(Payloads.ImportList payload) {
        if (Minecraft.getInstance().screen instanceof dev.remold.client.screen.ImportScreen screen) {
            screen.receive(payload);
        }
    }

    public static void handle(Payloads.Access payload) {
        ClientState.setAccess(payload.permissionLevel(), payload.listed());
    }

    public static void handle(Payloads.Notice payload) {
        toast(payload.success(), Component.translatable(payload.title()),
                Component.translatable(payload.message(), payload.args().toArray()));
    }

    public static void toast(boolean success, Component title, Component message) {
        Minecraft mc = Minecraft.getInstance();
        Component t = title.copy().withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED);
        mc.getToasts().addToast(SystemToast.multiline(mc, TOAST, t, message));
    }

    public static void appendTooltip(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty()) return;
        Annotation annotation = ClientState.annotations().get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (annotation == null || annotation.lines().isEmpty()) return;
        int insertAt = Math.min(1, lines.size());
        for (String line : annotation.lines()) {
            lines.add(insertAt++, TextUtil.parse(line));
        }
    }

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
        Tooltip tooltip = Tooltip.create(RemoldText.tr("button.title").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n")).append(RemoldText.tr("button.description").withStyle(ChatFormatting.GRAY)));
        IconButton button = new IconButton(guiLeft.getAsInt() + dx, guiTop.getAsInt() + dy, size, size,
                RemoldText.tr("name"), () -> icon, true, b -> openEditor(screen)) {
            @Override
            public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                setX(guiLeft.getAsInt() + dx);
                setY(guiTop.getAsInt() + dy);
                boolean show = canEdit()
                        && !(screen instanceof CreativeModeInventoryScreen creative && !creative.isInventoryOpen());

                active = show;
                setTooltip(show ? tooltip : null);
                if (show) super.renderWidget(g, mouseX, mouseY, partialTick);
            }
        };
        button.setTooltip(tooltip);
        return button;
    }
}
