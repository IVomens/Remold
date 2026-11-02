package dev.remold.client.screen;

import dev.remold.client.ClientState;
import dev.remold.client.RemoldClient;
import dev.remold.client.widget.UiDraw;
import dev.remold.data.Annotation;
import dev.remold.data.TextUtil;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Item-wide tooltips: every copy of the item, everywhere, shows these lines. Also the one-click
 * "make unobtainable" (remove every recipe for the item + red tooltip) and hiding from JEI/EMI.
 */
public class AnnotationScreen extends Screen {
    private static final int LINES = 4;
    private static final String UNOBTAINABLE = "&c&lITEM UNOBTAINABLE";

    private final Screen parent;
    private ItemStack target;
    private final String[] values = new String[LINES];
    private boolean hide;

    public AnnotationScreen(Screen parent, ItemStack target) {
        super(Component.literal("Item tooltip"));
        this.parent = parent;
        setTarget(target);
    }

    private void setTarget(ItemStack stack) {
        target = stack;
        Annotation existing = stack.isEmpty() ? null : ClientState.annotations().get(id());
        for (int i = 0; i < LINES; i++) {
            values[i] = existing != null && i < existing.lines().size() ? existing.lines().get(i) : "";
        }
        hide = existing != null && existing.hideFromViewers();
    }

    private ResourceLocation id() {
        return BuiltInRegistries.ITEM.getKey(target.getItem());
    }

    @Override
    protected void init() {
        int x0 = width / 2 - 110;
        addRenderableWidget(Button.builder(Component.literal(target.isEmpty() ? "Pick item..." : "Change item..."),
                b -> minecraft.setScreen(new ItemPickerScreen(this, s -> {
                    setTarget(s.copyWithCount(1));
                }))).bounds(x0 + 120, 32, 100, 20).build());
        for (int i = 0; i < LINES; i++) {
            int line = i;
            EditBox box = new EditBox(font, x0, 66 + i * 18, 220, 16, Component.empty());
            box.setMaxLength(256);
            box.setValue(values[i]);
            if (i == 0) box.setHint(Component.literal("e.g. &c&lITEM UNOBTAINABLE").withStyle(ChatFormatting.DARK_GRAY));
            box.setResponder(s -> values[line] = s);
            box.active = !target.isEmpty();
            addRenderableWidget(box);
        }
        int y = 66 + LINES * 18 + 2;
        CycleButton<Boolean> hideButton = CycleButton.onOffBuilder(hide)
                .create(x0, y, 220, 20, Component.literal("Hide from JEI / EMI"), (b, v) -> hide = v);
        hideButton.setTooltip(Tooltip.create(Component.literal("Hide this item from recipe viewers' item lists")));
        hideButton.active = !target.isEmpty();
        addRenderableWidget(hideButton);
        y += 24;

        Button save = Button.builder(Component.literal("Save tooltip").withStyle(ChatFormatting.GREEN), b -> send(annotation()))
                .bounds(x0, y, 108, 20).build();
        save.active = !target.isEmpty();
        addRenderableWidget(save);
        Button clear = Button.builder(Component.literal("Clear tooltip"), b -> {
            for (int i = 0; i < LINES; i++) values[i] = "";
            hide = false;
            send(Annotation.EMPTY);
            rebuildWidgets();
        }).bounds(x0 + 112, y, 108, 20).build();
        clear.active = !target.isEmpty();
        addRenderableWidget(clear);
        y = height - 24;

        Button unobtainable = Button.builder(Component.literal("Make unobtainable").withStyle(ChatFormatting.RED), b -> makeUnobtainable())
                .bounds(x0, y, 140, 20).build();
        unobtainable.setTooltip(Tooltip.create(Component.literal("Removes EVERY recipe that makes this item (from all mods and machines) and adds a red \"ITEM UNOBTAINABLE\" tooltip.\nUndo it per recipe from \"Recipes...\" (filter: changes).")));
        unobtainable.active = !target.isEmpty();
        addRenderableWidget(unobtainable);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x0 + 144, y, 76, 20).build());
    }

    private Annotation annotation() {
        List<String> lines = new ArrayList<>();
        for (String v : values) if (!v.isBlank()) lines.add(v);
        return new Annotation(lines, hide);
    }

    private void send(Annotation annotation) {
        if (target.isEmpty()) return;
        Services.PLATFORM.sendToServer(new Payloads.SetAnnotation(id(), annotation));
    }

    private void makeUnobtainable() {
        if (target.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        for (String v : values) if (!v.isBlank()) lines.add(v);
        if (!lines.contains(UNOBTAINABLE)) lines.addFirst(UNOBTAINABLE);
        for (int i = 0; i < LINES; i++) values[i] = i < lines.size() ? lines.get(i) : "";
        send(new Annotation(lines, hide));
        Services.PLATFORM.sendToServer(new Payloads.RecipeAction(Payloads.Action.REMOVE_ALL_FOR_ITEM, id(), true));
        RemoldClient.toast(true, "Making unobtainable", target.getHoverName().getString());
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x0 = width / 2 - 110;
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal("Shown on this item everywhere, for every player").withStyle(ChatFormatting.GRAY), width / 2, 18, 0xFFFFFF);
        UiDraw.slot(g, x0, 33, 18);
        g.renderItem(target, x0 + 1, 34);
        g.drawString(font, target.isEmpty() ? Component.literal("No item selected").withStyle(ChatFormatting.RED) : target.getHoverName(), x0 + 22, 38, 0xFFFFFF);
        g.drawString(font, "Tooltip lines (& colour codes)", x0, 56, 0xA0A0A0);
        if (!target.isEmpty()) {
            // live preview
            int px = x0 + 230;
            List<Component> preview = new ArrayList<>();
            preview.add(target.getHoverName());
            for (String v : values) if (!v.isBlank()) preview.add(TextUtil.parse(v));
            if (px + 100 < width) g.renderComponentTooltip(font, preview, px, 70);
            if (mouseX >= x0 && mouseX < x0 + 18 && mouseY >= 33 && mouseY < 51) {
                g.renderComponentTooltip(font, getTooltipFromItem(minecraft, target), mouseX, mouseY);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
