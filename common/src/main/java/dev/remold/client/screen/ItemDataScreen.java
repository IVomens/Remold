package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.data.TextUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public class ItemDataScreen extends Screen {
    private static final int LORE_LINES = 4;

    enum Toggle { DEFAULT, ON, OFF }

    private final Screen parent;
    private final ItemStack original;
    private final Consumer<ItemStack> onDone;

    private EditBox name, count, modelData;
    private final EditBox[] lore = new EditBox[LORE_LINES];
    private Toggle glint = Toggle.DEFAULT, unbreakable = Toggle.DEFAULT;
    private String rarity = "default";

    private String nameValue, countValue, modelValue;
    private final String[] loreValues = new String[LORE_LINES];

    public ItemDataScreen(Screen parent, ItemStack stack, Consumer<ItemStack> onDone) {
        super(RemoldText.tr("data.title"));
        this.parent = parent;
        this.original = stack.copy();
        this.onDone = onDone;
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        nameValue = customName == null ? "" : TextUtil.toCodes(customName);
        List<Component> lines = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
        for (int i = 0; i < LORE_LINES; i++) loreValues[i] = i < lines.size() ? TextUtil.toCodes(lines.get(i)) : "";
        countValue = Integer.toString(stack.getCount());
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        modelValue = cmd == null ? "" : Integer.toString(cmd.value());
        Boolean g = stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        glint = g == null ? Toggle.DEFAULT : g ? Toggle.ON : Toggle.OFF;
        unbreakable = stack.has(DataComponents.UNBREAKABLE) && !original.getItem().components().has(DataComponents.UNBREAKABLE) ? Toggle.ON : Toggle.DEFAULT;
        Rarity r = stack.get(DataComponents.RARITY);
        if (r != null && !Objects.equals(r, stack.getItem().components().get(DataComponents.RARITY))) {
            rarity = r.getSerializedName();
        }
    }

    @Override
    protected void init() {
        int x0 = width / 2 - 110;
        int y = 30;
        name = box(x0 + 24, y + 1, 196, nameValue, RemoldText.tr("data.name.placeholder").getString());
        name.setResponder(s -> nameValue = s);
        y += 22;
        for (int i = 0; i < LORE_LINES; i++) {
            int line = i;
            lore[i] = box(x0, y + 10 + i * 18, 220, loreValues[i], i == 0 ? RemoldText.tr("data.lore.placeholder").getString() : "");
            lore[i].setResponder(s -> loreValues[line] = s);
        }
        y += 10 + LORE_LINES * 18 + 2;
        count = box(x0 + 40, y, 40, countValue, "");
        count.setFilter(s -> s.matches("\\d{0,2}"));
        count.setResponder(s -> countValue = s);
        modelData = box(x0 + 170, y, 50, modelValue, RemoldText.tr("data.none").getString());
        modelData.setFilter(s -> s.matches("-?\\d{0,9}"));
        modelData.setResponder(s -> modelValue = s);
        modelData.setTooltip(Tooltip.create(RemoldText.tr("data.model.hint")));
        y += 20;

        addRenderableWidget(CycleButton.<Toggle>builder(t -> RemoldText.tr("data.toggle." + t.name().toLowerCase(Locale.ROOT)))
                .withValues(Toggle.values()).withInitialValue(glint)
                .create(x0, y, 72, 20, RemoldText.tr("data.glint"), (b, v) -> glint = v));
        List<String> rarities = new ArrayList<>(List.of("default"));
        for (Rarity r : Rarity.values()) rarities.add(r.getSerializedName());
        addRenderableWidget(CycleButton.<String>builder(s -> RemoldText.tr("data.rarity." + s))
                .withValues(rarities).withInitialValue(rarity)
                .create(x0 + 74, y, 72, 20, RemoldText.tr("data.rarity"), (b, v) -> rarity = v));
        addRenderableWidget(CycleButton.<Toggle>builder(t -> RemoldText.tr(t == Toggle.ON ? "data.yes" : "data.toggle.default"))
                .withValues(Toggle.DEFAULT, Toggle.ON).withInitialValue(unbreakable)
                .create(x0 + 148, y, 72, 20, RemoldText.tr("data.unbreakable"), (b, v) -> unbreakable = v));
        y += 22;

        Button unobtainable = Button.builder(RemoldText.tr("data.unobtainable").withStyle(ChatFormatting.RED), b -> {
            for (EditBox box : lore) {
                if (box.getValue().isBlank()) {
                    box.setValue("&c&lITEM UNOBTAINABLE");
                    return;
                }
            }
        }).bounds(x0, y, 128, 20).build();
        unobtainable.setTooltip(Tooltip.create(RemoldText.tr("data.unobtainable.hint")));
        addRenderableWidget(unobtainable);
        addRenderableWidget(Button.builder(RemoldText.tr("data.reset"), b -> {
            nameValue = "";
            for (int i = 0; i < LORE_LINES; i++) loreValues[i] = "";
            modelValue = "";
            glint = Toggle.DEFAULT;
            unbreakable = Toggle.DEFAULT;
            rarity = "default";
            rebuildWidgets();
        }).bounds(x0 + 132, y, 88, 20).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
            onDone.accept(build());
            minecraft.setScreen(parent);
        }).bounds(width / 2 - 102, height - 24, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 2, height - 24, 100, 20).build());
    }

    private EditBox box(int x, int y, int w, String value, String hint) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.empty());
        box.setMaxLength(256);
        box.setValue(value);
        if (!hint.isEmpty()) box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(box);
        return box;
    }

    private static <T> void reset(ItemStack stack, DataComponentType<T> type) {
        T def = stack.getItem().components().get(type);
        if (def == null) stack.remove(type);
        else stack.set(type, def);
    }

    ItemStack build() {
        ItemStack stack = original.copy();
        if (nameValue.isBlank()) reset(stack, DataComponents.CUSTOM_NAME);
        else stack.set(DataComponents.CUSTOM_NAME, TextUtil.parse(nameValue));

        List<Component> lines = new ArrayList<>();
        for (String line : loreValues) if (!line.isBlank()) lines.add(TextUtil.parse(line));
        if (lines.isEmpty()) reset(stack, DataComponents.LORE);
        else stack.set(DataComponents.LORE, new ItemLore(lines));

        switch (glint) {
            case DEFAULT -> reset(stack, DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
            case ON -> stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            case OFF -> stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
        }
        if (unbreakable == Toggle.ON) stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        else reset(stack, DataComponents.UNBREAKABLE);

        if (rarity.equals("default")) reset(stack, DataComponents.RARITY);
        else for (Rarity r : Rarity.values()) if (r.getSerializedName().equals(rarity)) stack.set(DataComponents.RARITY, r);

        if (modelValue.isBlank() || modelValue.equals("-")) reset(stack, DataComponents.CUSTOM_MODEL_DATA);
        else stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(Integer.parseInt(modelValue)));

        int n = countValue.isEmpty() ? 1 : Integer.parseInt(countValue);
        stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), n)));
        return stack;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x0 = width / 2 - 110;
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(font, RemoldText.tr("data.colours").withStyle(ChatFormatting.GRAY), width / 2, 18, 0xFFFFFF);
        ItemStack preview = build();
        g.renderItem(preview, x0 + 2, 30);
        g.renderItemDecorations(font, preview, x0 + 2, 30);
        g.drawString(font, RemoldText.tr("data.lore"), x0, 53, 0xA0A0A0);
        int y = 52 + 10 + LORE_LINES * 18 + 2;
        g.drawString(font, RemoldText.tr("data.count"), x0, y + 4, 0xA0A0A0);
        g.drawString(font, RemoldText.tr("data.model"), x0 + 110, y + 4, 0xA0A0A0);
        if (mouseX >= x0 && mouseX < x0 + 20 && mouseY >= 28 && mouseY < 48) {
            g.renderComponentTooltip(font, getTooltipFromItem(minecraft, preview), mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
