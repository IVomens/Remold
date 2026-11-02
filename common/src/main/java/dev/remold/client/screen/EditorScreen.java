package dev.remold.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.DataResult;
import com.google.gson.JsonObject;
import dev.remold.client.ClientState;
import dev.remold.client.RemoldClient;
import dev.remold.client.widget.IconButton;
import dev.remold.client.widget.UiDraw;
import dev.remold.core.EditorState;
import dev.remold.core.GhostIngredient;
import dev.remold.core.Machine;
import dev.remold.core.RecipeFactory;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The main editor. Recipe slots are "ghost" slots: they hold copies of items and never touch the
 * player's inventory. Items can be put in them by:
 * <ul>
 *     <li>clicking an item in your inventory (picks up a ghost copy), then clicking a slot</li>
 *     <li>the built-in item search ("Items...")</li>
 *     <li>dragging from JEI / EMI</li>
 *     <li>the "+" (move items) button on any recipe in JEI, which loads the whole recipe</li>
 * </ul>
 */
public class EditorScreen extends AbstractContainerScreen<EditorMenu> {
    public static final int W = 200;
    public static final int H = 236;
    public static final int INV_X = 20;
    public static final int INV_Y = 156;
    private static final int MAX_TEMPLATE_INPUTS = 18;
    private static final int MAX_TEMPLATE_OUTPUTS = 4;

    /** A recipe slot. Coordinates are relative to the panel and point at the slot's outer corner. */
    public record GhostSlot(boolean output, int index, int x, int y, int size) {
        int itemX() {
            return x + (size - 16) / 2;
        }

        int itemY() {
            return y + (size - 16) / 2;
        }
    }

    /** Somewhere a recipe viewer can drop an item. Coordinates are absolute screen coordinates. */
    public record DropTarget(Rect2i area, Consumer<ItemStack> accept, @Nullable Consumer<TagKey<Item>> acceptTag) {}

    @Nullable private final Screen parent;
    final EditorState state = ClientState.EDITOR;
    private ItemStack cursor = ItemStack.EMPTY;
    private final List<GhostSlot> ghostSlots = new ArrayList<>();
    private int selectedOutput;
    private EditBox idBox;
    private boolean settingId;

    public EditorScreen(EditorMenu menu, Inventory inventory, @Nullable Screen parent) {
        super(menu, inventory, Component.literal("Remold"));
        this.parent = parent;
        this.imageWidth = W;
        this.imageHeight = H;
        menu.screen = this;
    }

    RegistryAccess registries() {
        return minecraft.level.registryAccess();
    }

    RecipeManager recipes() {
        return minecraft.level.getRecipeManager();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        super.init();
        layoutSlots();
        int x = leftPos, y = topPos;
        Machine machine = state.machine;

        boolean template = machine.isTemplate();
        int machineWidth = template && machine.examples().size() > 1 ? 120 : 184;
        IconButton machineButton = new IconButton(x + 8, y + 15, machineWidth, 18, machine.name(), machine::icon, false,
                b -> minecraft.setScreen(new MachineSelectScreen(this, m -> {
                    RecipeFactory.setMachine(state, m, registries());
                    updateAutoId();
                })));
        machineButton.setTooltip(Tooltip.create(Component.literal("Machine: ").append(machine.name())
                .append(Component.literal("\n" + machine.id()).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("\nClick to choose another machine").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(machineButton);
        if (template && machine.examples().size() > 1) {
            Button prev = Button.builder(Component.literal("<"), b -> switchExample(-1)).bounds(x + 130, y + 15, 14, 18).build();
            Button next = Button.builder(Component.literal(">"), b -> switchExample(1)).bounds(x + 178, y + 15, 14, 18).build();
            Tooltip tip = Tooltip.create(Component.literal("Switch the existing recipe used as a template.\nEvery machine works differently, so Remold starts from a real recipe and lets you change its items and values."));
            prev.setTooltip(tip);
            next.setTooltip(tip);
            addRenderableWidget(prev);
            addRenderableWidget(next);
        }

        if (machine.kind().cooking()) {
            EditBox time = new EditBox(font, x + 150, y + 48, 42, 14, Component.literal("Cooking time"));
            time.setValue(Integer.toString(state.cookTime));
            time.setFilter(s -> s.matches("\\d{0,6}"));
            time.setResponder(s -> state.cookTime = s.isEmpty() ? 0 : Integer.parseInt(s));
            time.setTooltip(Tooltip.create(Component.literal("Cooking time in ticks (20 ticks = 1 second)")));
            addRenderableWidget(time);
            EditBox xp = new EditBox(font, x + 150, y + 76, 42, 14, Component.literal("Experience"));
            xp.setValue(Float.toString(state.experience));
            xp.setFilter(s -> s.matches("\\d{0,4}(\\.\\d{0,3})?"));
            xp.setResponder(s -> {
                try {
                    state.experience = s.isEmpty() ? 0 : Float.parseFloat(s);
                } catch (NumberFormatException ignored) {
                }
            });
            addRenderableWidget(xp);
        }
        if (template && state.template != null && !state.template.params.isEmpty()) {
            Button params = Button.builder(Component.literal("Values..."), b -> minecraft.setScreen(new ParamsScreen(this)))
                    .bounds(x + 144, y + 80, 48, 16).build();
            params.setTooltip(Tooltip.create(Component.literal("Edit numbers and other values of this recipe (time, energy, chance, fluids...)")));
            addRenderableWidget(params);
        }

        idBox = new EditBox(font, x + 8, y + 100, 184, 14, Component.literal("Recipe id"));
        idBox.setMaxLength(200);
        idBox.setHint(Component.literal("recipe id, e.g. remold:my_recipe").withStyle(ChatFormatting.DARK_GRAY));
        settingId = true;
        idBox.setValue(state.recipeId);
        settingId = false;
        idBox.setResponder(s -> {
            if (settingId) return;
            state.recipeId = s;
            state.autoId = s.isEmpty();
        });
        idBox.setTooltip(Tooltip.create(Component.literal("Recipe id. Use the id of an existing recipe to replace it, or a new id to add a recipe.")));
        addRenderableWidget(idBox);

        Button save = Button.builder(Component.literal("Save").withStyle(ChatFormatting.GREEN), b -> save(!hasShiftDown()))
                .bounds(x + 8, y + 117, 60, 16).build();
        save.setTooltip(Tooltip.create(Component.literal("Save and apply the recipe (Ctrl+S)\n")
                .append(Component.literal("Shift-click to save without reloading, to apply many changes at once.").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(save);
        Button clear = Button.builder(Component.literal("Clear"), b -> {
            state.clear();
            cursor = ItemStack.EMPTY;
            rebuildWidgets();
        }).bounds(x + 70, y + 117, 60, 16).build();
        clear.setTooltip(Tooltip.create(Component.literal("Empty all slots and start a new recipe")));
        addRenderableWidget(clear);
        Button items = Button.builder(Component.literal("Items..."), b -> minecraft.setScreen(new ItemPickerScreen(this, stack -> cursor = stack)))
                .bounds(x + 132, y + 117, 60, 16).build();
        items.setTooltip(Tooltip.create(Component.literal("Search every item in the game")));
        addRenderableWidget(items);

        Button data = Button.builder(Component.literal("Item data..."), b -> openItemData())
                .bounds(x + 8, y + 135, 60, 16).build();
        data.setTooltip(Tooltip.create(Component.literal("Change the output's name, lore, glint, rarity... ")
                .append(Component.literal("\ne.g. a red \"ITEM UNOBTAINABLE\" line").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(data);
        Button tooltip = Button.builder(Component.literal("Tooltip..."), b -> openAnnotation())
                .bounds(x + 70, y + 135, 60, 16).build();
        tooltip.setTooltip(Tooltip.create(Component.literal("Add tooltip lines to an item everywhere (not just this recipe's output), mark items as unobtainable, or hide them from JEI/EMI")));
        addRenderableWidget(tooltip);
        Button browse = Button.builder(Component.literal("Recipes..."), b -> openBrowser())
                .bounds(x + 132, y + 135, 60, 16).build();
        browse.setTooltip(Tooltip.create(Component.literal("Find existing recipes to edit, remove or restore")));
        addRenderableWidget(browse);
    }

    private void layoutSlots() {
        ghostSlots.clear();
        Machine.Kind kind = state.machine.kind();
        switch (kind) {
            case SHAPED, SHAPELESS -> {
                for (int i = 0; i < 9; i++) ghostSlots.add(new GhostSlot(false, i, 30 + (i % 3) * 18, 38 + (i / 3) * 18, 18));
                ghostSlots.add(new GhostSlot(true, 0, 126, 52, 26));
            }
            case SMITHING -> {
                for (int i = 0; i < 3; i++) ghostSlots.add(new GhostSlot(false, i, 16 + i * 18, 56, 18));
                ghostSlots.add(new GhostSlot(true, 0, 112, 52, 26));
            }
            case TEMPLATE -> {
                for (int i = 0; i < Math.min(MAX_TEMPLATE_INPUTS, state.inputs.length); i++)
                    ghostSlots.add(new GhostSlot(false, i, 8 + (i % 6) * 18, 38 + (i / 6) * 18, 18));
                for (int i = 0; i < Math.min(MAX_TEMPLATE_OUTPUTS, state.outputs.length); i++)
                    ghostSlots.add(new GhostSlot(true, i, 150 + (i % 2) * 18, 38 + (i / 2) * 18, 18));
            }
            default -> { // single input machines
                ghostSlots.add(new GhostSlot(false, 0, 40, 56, 18));
                ghostSlots.add(new GhostSlot(true, 0, 102, 52, 26));
            }
        }
    }

    private int arrowX() {
        return switch (state.machine.kind()) {
            case SHAPED, SHAPELESS -> 94;
            case SMITHING -> 80;
            case TEMPLATE -> 122;
            default -> 68;
        };
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            UiDraw.slot(g, leftPos + slot.x - 1, topPos + slot.y - 1, 18);
        }
        Machine.Kind kind = state.machine.kind();
        if (state.machine.isTemplate() && state.template == null) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(
                    state.templateError == null ? "No template" : state.templateError), imageWidth - 16);
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                g.drawString(font, lines.get(i), leftPos + 8, topPos + 38 + i * 10, 0xFFAA0000, false);
            }
            return;
        }
        UiDraw.arrow(g, leftPos + arrowX(), topPos + (kind == Machine.Kind.TEMPLATE ? 48 : 57));
        if (kind.cooking()) {
            UiDraw.flame(g, leftPos + 42, topPos + 76);
        }
        for (GhostSlot s : ghostSlots) {
            UiDraw.slot(g, leftPos + s.x(), topPos + s.y(), s.size());
            int ix = leftPos + s.itemX(), iy = topPos + s.itemY();
            if (s.output()) {
                ItemStack stack = state.outputs[s.index()];
                g.renderItem(stack, ix, iy);
                g.renderItemDecorations(font, stack, ix, iy);
            } else {
                GhostIngredient ghost = state.inputs[s.index()];
                g.renderItem(ghost.display(), ix, iy);
                if (ghost.tag() != null) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 200);
                    g.drawString(font, "#", ix, iy, 0xFFFFAA00, true);
                    g.pose().popPose();
                }
            }
        }
        if (kind == Machine.Kind.TEMPLATE) {
            if (state.inputs.length > MAX_TEMPLATE_INPUTS) {
                g.drawString(font, "+" + (state.inputs.length - MAX_TEMPLATE_INPUTS) + " more", leftPos + 8, topPos + 92, 0xFF404040, false);
            }
            if (state.outputs.length > MAX_TEMPLATE_OUTPUTS) {
                g.drawString(font, "+" + (state.outputs.length - MAX_TEMPLATE_OUTPUTS), leftPos + 150, topPos + 74, 0xFF404040, false);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 8, 5, 0xFF404040, false);
        if (state.loadedFrom != null) {
            String editing = "editing " + state.loadedFrom;
            g.drawString(font, font.plainSubstrByWidth(editing, imageWidth - 50), 50, 5, 0xFF7A5C00, false);
        }
        Machine machine = state.machine;
        if (machine.isTemplate() && machine.examples().size() > 1) {
            String label = (state.exampleIndex + 1) + "/" + machine.examples().size();
            g.drawString(font, label, 161 - font.width(label) / 2, 20, 0xFF404040, false);
        }
        if (machine.kind().cooking()) {
            g.drawString(font, "Time", 150, 38, 0xFF404040, false);
            g.drawString(font, "XP", 150, 66, 0xFF404040, false);
        }
        if (machine.kind() == Machine.Kind.SHAPELESS) {
            g.drawString(font, "any order", 118, 82, 0xFF707070, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        GhostSlot hovered = ghostAt(mouseX, mouseY);
        if (hovered != null) {
            UiDraw.highlight(g, leftPos + hovered.x() + 1, topPos + hovered.y() + 1, hovered.size() - 2);
        }
        if (!cursor.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.renderItem(cursor, mouseX - 8, mouseY - 8);
            g.renderItemDecorations(font, cursor, mouseX - 8, mouseY - 8);
            g.pose().popPose();
        } else if (hovered != null) {
            renderGhostTooltip(g, hovered, mouseX, mouseY);
        } else {
            renderTooltip(g, mouseX, mouseY);
        }
    }

    private void renderGhostTooltip(GuiGraphics g, GhostSlot slot, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        if (slot.output()) {
            ItemStack stack = state.outputs[slot.index()];
            if (!stack.isEmpty()) lines.addAll(getTooltipFromItem(minecraft, stack));
            else lines.add(Component.literal("Output").withStyle(ChatFormatting.YELLOW));
            lines.add(Component.literal("Click with an item to set the output").withStyle(ChatFormatting.DARK_GRAY));
            if (!stack.isEmpty()) {
                lines.add(Component.literal("Scroll to change the amount, right-click to clear").withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            GhostIngredient ghost = state.inputs[slot.index()];
            if (ghost.isEmpty()) {
                lines.add(Component.literal(state.machine.kind() == Machine.Kind.SMITHING
                        ? new String[]{"Template", "Base", "Addition"}[slot.index()] : "Input").withStyle(ChatFormatting.YELLOW));
                lines.add(Component.literal("Click with an item to set it (hold the mouse to paint)").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.addAll(getTooltipFromItem(minecraft, ghost.display()));
                if (ghost.tag() != null) {
                    lines.add(Component.literal("Accepts any #" + ghost.tag().location()).withStyle(ChatFormatting.GOLD));
                }
                lines.add(Component.literal("Middle-click or Ctrl-click: use an item tag instead").withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.literal("Right-click to clear").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ input

    @Nullable
    private GhostSlot ghostAt(double mouseX, double mouseY) {
        if (state.machine.isTemplate() && state.template == null) return null;
        for (GhostSlot s : ghostSlots) {
            int x = leftPos + s.x(), y = topPos + s.y();
            if (mouseX >= x && mouseX < x + s.size() && mouseY >= y && mouseY < y + s.size()) return s;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        GhostSlot slot = ghostAt(mouseX, mouseY);
        if (slot != null) {
            clickGhost(slot, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        GhostSlot slot = ghostAt(mouseX, mouseY);
        if (button == 0 && slot != null && !slot.output() && !cursor.isEmpty()) {
            setInput(slot.index(), GhostIngredient.of(cursor));
            return true;
        }
        if (button == 1 && slot != null && !slot.output()) {
            setInput(slot.index(), GhostIngredient.EMPTY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        GhostSlot slot = ghostAt(mouseX, mouseY);
        if (slot != null && slot.output()) {
            ItemStack stack = state.outputs[slot.index()];
            if (!stack.isEmpty()) {
                int step = hasShiftDown() ? 8 : 1;
                stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), stack.getCount() + (scrollY > 0 ? step : -step))));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void clickGhost(GhostSlot slot, int button) {
        boolean tagClick = button == 2 || (button == 0 && hasControlDown());
        if (slot.output()) {
            selectedOutput = slot.index();
            ItemStack current = state.outputs[slot.index()];
            if (button == 1) {
                setOutput(slot.index(), ItemStack.EMPTY);
            } else if (!cursor.isEmpty()) {
                setOutput(slot.index(), cursor.copy());
            } else if (!current.isEmpty()) {
                cursor = current.copy();
                setOutput(slot.index(), ItemStack.EMPTY);
            }
            return;
        }
        GhostIngredient current = state.inputs[slot.index()];
        if (button == 1 || (button == 0 && hasShiftDown())) {
            setInput(slot.index(), GhostIngredient.EMPTY);
        } else if (tagClick) {
            if (current.isEmpty() && !cursor.isEmpty()) current = GhostIngredient.of(cursor);
            setInput(slot.index(), current.cycleTag());
        } else if (!cursor.isEmpty()) {
            setInput(slot.index(), GhostIngredient.of(cursor));
        } else if (!current.isEmpty()) {
            cursor = current.stack().copy();
            setInput(slot.index(), GhostIngredient.EMPTY);
        }
    }

    private void setInput(int index, GhostIngredient ghost) {
        state.inputs[index] = ghost;
    }

    private void setOutput(int index, ItemStack stack) {
        state.outputs[index] = stack;
        if (index == 0) updateAutoId();
    }

    void updateAutoId() {
        if (!state.autoId) return;
        state.recipeId = RecipeFactory.suggestId(state, recipes());
        if (idBox != null) {
            settingId = true;
            idBox.setValue(state.recipeId);
            settingId = false;
        }
    }

    @Override
    protected void slotClicked(@Nullable Slot slot, int slotId, int button, ClickType type) {
        // Never forwarded to the server: inventory slots only hand out ghost copies.
        if (slot == null) {
            cursor = ItemStack.EMPTY;
            return;
        }
        if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE || type == ClickType.CLONE) {
            if (slot.hasItem()) {
                cursor = slot.getItem().copy();
                if (button == 1) cursor.setCount(1);
            } else {
                cursor = ItemStack.EMPTY;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (!cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
                return true;
            }
            onClose();
            return true;
        }
        if (keyCode == InputConstants.KEY_S && hasControlDown()) {
            save(!hasShiftDown());
            return true;
        }
        if (getFocused() instanceof EditBox box && box.isFocused() && keyCode != InputConstants.KEY_TAB) {
            box.keyPressed(keyCode, scanCode, modifiers);
            return true; // don't let typing "e" close the screen
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // Not super.onClose(): that would tell the server to close a container it never opened.
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ actions

    private void switchExample(int delta) {
        RecipeFactory.loadExample(state, state.exampleIndex + delta, registries());
        rebuildWidgets();
    }

    private void save(boolean apply) {
        DataResult<ResourceLocation> id = RecipeFactory.parseId(state.recipeId.isEmpty() && state.autoId
                ? RecipeFactory.suggestId(state, recipes()) : state.recipeId);
        if (id.error().isPresent()) {
            RemoldClient.toast(false, "Can't save", id.error().get().message());
            return;
        }
        DataResult<JsonObject> json = RecipeFactory.build(state, registries());
        if (json.error().isPresent()) {
            RemoldClient.toast(false, "Can't save", json.error().get().message());
            return;
        }
        Services.PLATFORM.sendToServer(new Payloads.SaveRecipe(id.getOrThrow(), json.getOrThrow().toString(), apply));
        state.loadedFrom = id.getOrThrow();
        state.recipeId = id.getOrThrow().toString();
        state.autoId = false;
    }

    private int outputIndexForData() {
        if (selectedOutput < state.outputs.length && !state.outputs[selectedOutput].isEmpty()) return selectedOutput;
        for (int i = 0; i < state.outputs.length; i++) if (!state.outputs[i].isEmpty()) return i;
        return -1;
    }

    private void openItemData() {
        int index = outputIndexForData();
        if (index < 0) {
            RemoldClient.toast(false, "No output", "Put an item in the output slot first.");
            return;
        }
        minecraft.setScreen(new ItemDataScreen(this, state.outputs[index], edited -> state.outputs[index] = edited));
    }

    private void openAnnotation() {
        ItemStack target = !cursor.isEmpty() ? cursor : state.output();
        minecraft.setScreen(new AnnotationScreen(this, target.copyWithCount(1)));
    }

    private void openBrowser() {
        ItemStack output = state.output();
        minecraft.setScreen(new RecipeBrowserScreen(this, output.isEmpty() ? "" : output.getHoverName().getString()));
    }

    /** Loads an existing recipe into the editor (from the browser or a recipe viewer). */
    public void load(RecipeHolder<?> holder) {
        RecipeFactory.load(state, holder, recipes(), registries());
        cursor = ItemStack.EMPTY;
        selectedOutput = 0;
        rebuildWidgets();
    }

    /** Called when a template requested from the server arrives. */
    public void onTemplateReceived(ResourceLocation id) {
        Machine machine = state.machine;
        if (machine.isTemplate() && state.template == null && !machine.examples().isEmpty()
                && machine.examples().get(Math.floorMod(state.exampleIndex, machine.examples().size())).id().equals(id)) {
            RecipeFactory.loadExample(state, state.exampleIndex, registries());
            rebuildWidgets();
        }
    }

    /** Loads loose items when a recipe viewer shows something that isn't a real recipe. */
    public void loadItems(List<ItemStack> inputs, ItemStack output, boolean shaped) {
        RecipeFactory.loadItems(state, inputs, output, shaped, registries());
        updateAutoId();
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ recipe viewer support

    /** Slots that recipe viewers (JEI, EMI, ...) can drop items onto. */
    public List<DropTarget> dropTargets() {
        List<DropTarget> targets = new ArrayList<>();
        if (state.machine.isTemplate() && state.template == null) return targets;
        for (GhostSlot s : ghostSlots) {
            Rect2i area = new Rect2i(leftPos + s.x(), topPos + s.y(), s.size(), s.size());
            if (s.output()) {
                targets.add(new DropTarget(area, stack -> setOutput(s.index(), stack.copy()), null));
            } else {
                targets.add(new DropTarget(area, stack -> setInput(s.index(), GhostIngredient.of(stack)),
                        tag -> GhostIngredient.tagItems(tag).stream().findFirst()
                                .ifPresent(first -> setInput(s.index(), new GhostIngredient(new ItemStack(first), tag)))));
            }
        }
        return targets;
    }

    /** The item shown in the ghost slot under the mouse, so viewers can show its recipes (R / U keys). */
    public ItemStack ghostStackAt(double mouseX, double mouseY) {
        GhostSlot s = ghostAt(mouseX, mouseY);
        if (s == null) return ItemStack.EMPTY;
        return s.output() ? state.outputs[s.index()] : state.inputs[s.index()].display();
    }

    @Nullable
    public Rect2i ghostAreaAt(double mouseX, double mouseY) {
        GhostSlot s = ghostAt(mouseX, mouseY);
        return s == null ? null : new Rect2i(leftPos + s.x(), topPos + s.y(), s.size(), s.size());
    }

    /** Lets a viewer's item list be used as a palette: clicking an item there picks up a ghost copy. */
    public void pickUp(ItemStack stack) {
        cursor = stack.copy();
    }
}
