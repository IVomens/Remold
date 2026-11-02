package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.core.RecipeUsage;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Change many recipes at once: replace an item everywhere, remove every recipe that uses an item,
 * or remove every recipe from a mod. Shows which recipes will change before anything happens, and
 * the whole action is one undo step.
 */
public class BulkScreen extends Screen {
    private static final int PREVIEW_LINES = 8;

    private final Screen parent;
    private Payloads.BulkKind kind = Payloads.BulkKind.REPLACE_ITEM;
    private ItemStack from = ItemStack.EMPTY, to = ItemStack.EMPTY;
    private String mod = "";
    private int scope;
    private List<ResourceLocation> preview = List.of();
    private Button run;

    public BulkScreen(Screen parent) {
        super(RemoldText.tr("bulk.title"));
        this.parent = parent;
    }

    private int x0() {
        return width / 2 - 120;
    }

    @Override
    protected void init() {
        int x0 = x0();
        addRenderableWidget(CycleButton.<Payloads.BulkKind>builder(k -> RemoldText.tr("bulk.kind." + k.name().toLowerCase(Locale.ROOT)))
                .withValues(Payloads.BulkKind.values()).withInitialValue(kind).displayOnlyValue()
                .create(x0, 24, 240, 20, RemoldText.tr("bulk.kind"), (b, v) -> {
                    kind = v;
                    scope = 0;
                    rebuildWidgets();
                }));
        switch (kind) {
            case REPLACE_ITEM -> {
                slot(x0 + 70, 52, "bulk.from", from, s -> from = s);
                slot(x0 + 170, 52, "bulk.to", to, s -> to = s);
                scopeButton(3, "bulk.scope.replace");
            }
            case REMOVE_USING_ITEM -> slot(x0 + 70, 52, "bulk.item", from, s -> from = s);
            case REMOVE_FROM_MOD -> {
                EditBox box = new EditBox(font, x0 + 70, 53, 170, 16, RemoldText.tr("bulk.mod"));
                box.setValue(mod);
                box.setHint(RemoldText.tr("bulk.mod.placeholder").withStyle(ChatFormatting.DARK_GRAY));
                box.setResponder(s -> {
                    mod = s.trim().toLowerCase(Locale.ROOT);
                    updatePreview();
                });
                addRenderableWidget(box);
                scopeButton(2, "bulk.scope.mod");
            }
        }
        run = Button.builder(RemoldText.tr("bulk.run").withStyle(ChatFormatting.RED), b -> run())
                .bounds(width / 2 - 102, height - 28, 100, 20).build();
        run.setTooltip(Tooltip.create(RemoldText.tr("bulk.run.hint")));
        addRenderableWidget(run);
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 + 2, height - 28, 100, 20).build());
        updatePreview();
    }

    private void slot(int x, int y, String key, ItemStack value, java.util.function.Consumer<ItemStack> setter) {
        ItemSlotWidget slot = new ItemSlotWidget(this, x, y, RemoldText.tr(key + ".hint"), s -> {
            setter.accept(s);
            updatePreview();
        });
        slot.setStack(value);
        addRenderableWidget(slot);
    }

    private void scopeButton(int options, String key) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < options; i++) values.add(i);
        addRenderableWidget(CycleButton.<Integer>builder(i -> RemoldText.tr(key + "." + i))
                .withValues(values).withInitialValue(scope)
                .create(x0(), 78, 240, 20, RemoldText.tr("bulk.scope"), (b, v) -> {
                    scope = v;
                    updatePreview();
                }));
    }

    /** What the server will (at most) change, computed from the client's copy of the recipes. */
    private void updatePreview() {
        var level = minecraft.level;
        var registries = level.registryAccess();
        List<ResourceLocation> ids = new ArrayList<>();
        for (RecipeHolder<?> h : level.getRecipeManager().getRecipes()) {
            boolean hit = switch (kind) {
                case REPLACE_ITEM -> !from.isEmpty() && !to.isEmpty() && !ItemStack.isSameItem(from, to)
                        && ((scope != 2 && RecipeUsage.usesAny(h.value(), Set.of(from.getItem())))
                        || (scope != 1 && RecipeUsage.produces(h.value(), Set.of(from.getItem()), registries)));
                case REMOVE_USING_ITEM -> !from.isEmpty() && RecipeUsage.uses(h.value(), from);
                case REMOVE_FROM_MOD -> !mod.isEmpty() && mod.equals(scope == 1
                        ? outputNamespace(h, registries) : h.id().getNamespace());
            };
            if (hit) ids.add(h.id());
        }
        ids.sort(null);
        preview = ids;
        if (run != null) run.active = !ids.isEmpty();
    }

    private static String outputNamespace(RecipeHolder<?> h, net.minecraft.core.HolderLookup.Provider registries) {
        try {
            return BuiltInRegistries.ITEM.getKey(h.value().getResultItem(registries).getItem()).getNamespace();
        } catch (Exception e) {
            return "";
        }
    }

    private void run() {
        if (kind != Payloads.BulkKind.REPLACE_ITEM && !hasAltDown()) {
            var lost = RemovalCheck.lost(minecraft, new java.util.HashSet<>(preview), java.util.Set.of());
            if (!lost.isEmpty()) {
                boolean apply = !hasShiftDown();
                minecraft.setScreen(new WarningScreen(parent, lost, () -> send(apply)));
                return;
            }
        }
        send(!hasShiftDown());
        onClose();
    }

    private void send(boolean apply) {
        String a = switch (kind) {
            case REMOVE_FROM_MOD -> mod;
            default -> BuiltInRegistries.ITEM.getKey(from.getItem()).toString();
        };
        String b = to.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(to.getItem()).toString();
        Services.PLATFORM.sendToServer(new Payloads.Bulk(kind, a, b, scope, apply));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x0 = x0();
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        switch (kind) {
            case REPLACE_ITEM -> {
                g.drawString(font, RemoldText.tr("bulk.from"), x0, 57, 0xE0E0E0);
                g.drawString(font, RemoldText.tr("bulk.to"), x0 + 120, 57, 0xE0E0E0);
            }
            case REMOVE_USING_ITEM -> g.drawString(font, RemoldText.tr("bulk.item"), x0, 57, 0xE0E0E0);
            case REMOVE_FROM_MOD -> g.drawString(font, RemoldText.tr("bulk.mod"), x0, 57, 0xE0E0E0);
        }
        int y = 108;
        g.drawString(font, RemoldText.tr(kind == Payloads.BulkKind.REPLACE_ITEM ? "bulk.preview.up_to" : "bulk.preview", preview.size())
                .withStyle(preview.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.YELLOW), x0, y, 0xFFFFFF);
        if (kind == Payloads.BulkKind.REPLACE_ITEM) {
            g.drawString(font, RemoldText.tr("bulk.preview.tags").withStyle(ChatFormatting.GRAY), x0, y + 10, 0xFFFFFF);
            y += 10;
        }
        int lines = Math.min(PREVIEW_LINES, Math.max(0, (height - 40 - (y + 14)) / 10));
        for (int i = 0; i < Math.min(lines, preview.size()); i++) {
            g.drawString(font, font.plainSubstrByWidth(preview.get(i).toString(), 240), x0 + 6, y + 14 + i * 10, 0xA0A0A0);
        }
        if (preview.size() > lines && lines > 0) {
            g.drawString(font, RemoldText.tr("editor.more", preview.size() - lines), x0 + 6, y + 14 + lines * 10, 0x808080);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
