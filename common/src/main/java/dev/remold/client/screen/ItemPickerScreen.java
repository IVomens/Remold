package dev.remold.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Search every item in the game, for players without a recipe viewer.
 * Type {@code @modid} to filter by mod, like in JEI.
 */
public class ItemPickerScreen extends Screen {
    private static List<ItemStack> cache;

    private final Screen parent;
    private final Consumer<ItemStack> onPick;
    private EditBox search;
    private List<ItemStack> filtered = List.of();
    private int scrollRow;
    private int cols, rows, gridX, gridY;

    public ItemPickerScreen(Screen parent, Consumer<ItemStack> onPick) {
        super(Component.literal("Pick an item"));
        this.parent = parent;
        this.onPick = onPick;
    }

    private List<ItemStack> allItems() {
        if (cache == null) {
            List<ItemStack> list = new ArrayList<>();
            try {
                // The creative search tab contains variants too (potions, enchanted books, painted items...).
                CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(), true, minecraft.level.registryAccess());
                list.addAll(CreativeModeTabs.searchTab().getDisplayItems());
            } catch (Exception ignored) {
            }
            LinkedHashSet<net.minecraft.world.item.Item> seen = new LinkedHashSet<>();
            list.forEach(s -> seen.add(s.getItem()));
            for (var item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR && !seen.contains(item)) list.add(new ItemStack(item));
            }
            cache = list;
        }
        return cache;
    }

    @Override
    protected void init() {
        search = new EditBox(font, width / 2 - 100, 22, 200, 16, Component.literal("Search"));
        search.setHint(Component.literal("Search items, @mod to filter by mod").withStyle(ChatFormatting.DARK_GRAY));
        search.setResponder(s -> refresh());
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
        cols = Math.max(1, Math.min(18, (width - 40) / 18));
        rows = Math.max(1, (height - 80) / 18);
        gridX = (width - cols * 18) / 2;
        gridY = 44;
        refresh();
        setInitialFocus(search);
    }

    private void refresh() {
        String q = search.getValue().toLowerCase(Locale.ROOT).trim();
        String mod = null;
        if (q.startsWith("@")) {
            int space = q.indexOf(' ');
            mod = space < 0 ? q.substring(1) : q.substring(1, space);
            q = space < 0 ? "" : q.substring(space + 1).trim();
        }
        String text = q, modFilter = mod;
        filtered = allItems().stream().filter(stack -> {
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (modFilter != null && !id.getNamespace().startsWith(modFilter)) return false;
            return text.isEmpty() || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(text) || id.toString().contains(text);
        }).toList();
        scrollRow = 0;
    }

    private int maxScroll() {
        return Math.max(0, (filtered.size() + cols - 1) / cols - rows);
    }

    private int indexAt(double mouseX, double mouseY) {
        if (mouseX < gridX || mouseY < gridY) return -1;
        int c = (int) ((mouseX - gridX) / 18), r = (int) ((mouseY - gridY) / 18);
        if (c >= cols || r >= rows) return -1;
        int i = (scrollRow + r) * cols + c;
        return i < filtered.size() ? i : -1;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.fill(gridX - 2, gridY - 2, gridX + cols * 18 + 2, gridY + rows * 18 + 2, 0x80000000);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scrollRow + r) * cols + c;
                if (i >= filtered.size()) break;
                g.renderItem(filtered.get(i), gridX + c * 18 + 1, gridY + r * 18 + 1);
            }
        }
        g.drawString(font, filtered.size() + " items", gridX, gridY + rows * 18 + 4, 0x808080);
        int hovered = indexAt(mouseX, mouseY);
        if (hovered >= 0) {
            int c = (hovered % cols), r = hovered / cols - scrollRow;
            g.fill(gridX + c * 18, gridY + r * 18, gridX + c * 18 + 18, gridY + r * 18 + 18, 0x40FFFFFF);
            ItemStack stack = filtered.get(hovered);
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, stack));
            lines.add(Component.literal(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int i = indexAt(mouseX, mouseY);
        if (i >= 0) {
            ItemStack stack = filtered.get(i).copy();
            if (button == 0 && hasShiftDown()) stack.setCount(stack.getMaxStackSize());
            onPick.accept(stack);
            minecraft.setScreen(parent);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scrollY) * (hasShiftDown() ? rows : 1)));
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
