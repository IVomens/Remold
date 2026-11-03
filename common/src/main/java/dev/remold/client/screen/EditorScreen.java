package dev.remold.client.screen;

import dev.remold.RemoldText;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.DataResult;
import com.google.gson.JsonObject;
import dev.remold.client.ClientState;
import dev.remold.client.RemoldClient;
import dev.remold.client.widget.IconButton;
import dev.remold.client.widget.UiDraw;
import dev.remold.core.ConflictCheck;
import dev.remold.core.Machines;
import dev.remold.core.ProgressionCheck;
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
import net.minecraft.client.gui.screens.ConfirmScreen;
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

public class EditorScreen extends AbstractContainerScreen<EditorMenu> {
    public static final int W = 200;
    public static final int H = 236;
    public static final int INV_X = 20;
    public static final int INV_Y = 156;
    private static final int MAX_TEMPLATE_INPUTS = 18;
    private static final int MAX_TEMPLATE_OUTPUTS = 4;

    public record GhostSlot(boolean output, int index, int x, int y, int size) {
        int itemX() {
            return x + (size - 16) / 2;
        }

        int itemY() {
            return y + (size - 16) / 2;
        }
    }

    public record DropTarget(Rect2i area, Consumer<ItemStack> accept, @Nullable Consumer<TagKey<Item>> acceptTag) {}

    @Nullable private final Screen parent;
    final EditorState state = ClientState.EDITOR;
    private ItemStack cursor = ItemStack.EMPTY;
    private final List<GhostSlot> ghostSlots = new ArrayList<>();
    private int selectedOutput;
    private EditBox idBox;
    private boolean settingId;

    public EditorScreen(EditorMenu menu, Inventory inventory, @Nullable Screen parent) {
        super(menu, inventory, RemoldText.tr("name"));
        this.parent = parent;
        this.imageWidth = W;
        this.imageHeight = H;
        menu.screen = this;
    }

    RegistryAccess registries() {
        return net.minecraft.client.Minecraft.getInstance().level.registryAccess();
    }

    RecipeManager recipes() {
        return net.minecraft.client.Minecraft.getInstance().level.getRecipeManager();
    }

    private boolean showing() {
        return net.minecraft.client.Minecraft.getInstance().screen == this;
    }

    @Override
    protected void init() {
        super.init();
        layoutSlots();
        int x = leftPos, y = topPos;
        Machine machine = state.machine;

        boolean template = machine.isTemplate();
        int machineWidth = template && machine.examples().size() > 1 ? 120 : 184;
        if (state.checklistMod != null) {
            Button nextItem = Button.builder(RemoldText.tr("editor.next"), b -> {
                net.minecraft.world.item.Item next = Checklist.nextToDo(state.checklistMod, state.output().isEmpty() ? null : state.output().getItem());
                if (next == null) RemoldClient.toast(true, RemoldText.tr("checklist.all_done"), RemoldText.tr("checklist.all_done.message"));
                else startChecklistItem(state.checklistMod, next);
            }).bounds(x + imageWidth - 52, y + 2, 46, 12).build();
            nextItem.setTooltip(Tooltip.create(RemoldText.tr("editor.next.hint")));
            addRenderableWidget(nextItem);
        }
        IconButton machineButton = new IconButton(x + 8, y + 15, machineWidth, 18, machine.name(), machine::icon, false,
                b -> minecraft.setScreen(new MachineSelectScreen(this, m -> {
                    RecipeFactory.setMachine(state, m, registries());
                    updateAutoId();
                })));
        machineButton.setTooltip(Tooltip.create(RemoldText.tr("editor.machine", machine.name())
                .append(Component.literal("\n" + machine.id()).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("\n")).append(RemoldText.tr("editor.machine.hint").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(machineButton);
        if (template && machine.examples().size() > 1) {
            Button prev = Button.builder(Component.literal("<"), b -> switchExample(-1)).bounds(x + 130, y + 15, 14, 18).build();
            Button next = Button.builder(Component.literal(">"), b -> switchExample(1)).bounds(x + 178, y + 15, 14, 18).build();
            Tooltip tip = Tooltip.create(RemoldText.tr("editor.example.hint"));
            prev.setTooltip(tip);
            next.setTooltip(tip);
            addRenderableWidget(prev);
            addRenderableWidget(next);
        }

        if (machine.kind().cooking()) {
            EditBox time = new EditBox(font, x + 150, y + 48, 42, 14, RemoldText.tr("editor.cook_time"));
            time.setValue(Integer.toString(state.cookTime));
            time.setFilter(s -> s.matches("\\d{0,6}"));
            time.setResponder(s -> state.cookTime = s.isEmpty() ? 0 : Integer.parseInt(s));
            time.setTooltip(Tooltip.create(RemoldText.tr("editor.cook_time.hint")));
            addRenderableWidget(time);
            EditBox xp = new EditBox(font, x + 150, y + 76, 42, 14, RemoldText.tr("editor.xp"));
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
            Button params = Button.builder(RemoldText.tr("editor.values"), b -> minecraft.setScreen(new ParamsScreen(this)))
                    .bounds(x + 144, y + 80, 48, 16).build();
            params.setTooltip(Tooltip.create(RemoldText.tr("editor.values.hint")));
            addRenderableWidget(params);
        }

        idBox = new EditBox(font, x + 8, y + 100, 184, 14, RemoldText.tr("editor.id"));
        idBox.setMaxLength(200);
        idBox.setHint(RemoldText.tr("editor.id.placeholder").withStyle(ChatFormatting.DARK_GRAY));
        settingId = true;
        idBox.setValue(state.recipeId);
        settingId = false;
        idBox.setResponder(s -> {
            if (settingId) return;
            state.recipeId = s;
            state.autoId = s.isEmpty();
        });
        idBox.setTooltip(Tooltip.create(RemoldText.tr("editor.id.hint")));
        addRenderableWidget(idBox);

        Button save = Button.builder(RemoldText.tr("editor.save").withStyle(ChatFormatting.GREEN), b -> save(!hasShiftDown()))
                .bounds(x + 8, y + 117, 60, 16).build();
        save.setTooltip(Tooltip.create(RemoldText.tr("editor.save.hint").append(Component.literal("\n"))
                .append(RemoldText.tr("editor.save.shift").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(save);
        Button clear = Button.builder(RemoldText.tr("editor.clear"), b -> replaceDraft(() -> {
            state.clear();
            cursor = ItemStack.EMPTY;
            state.markClean();
            rebuildWidgets();
        })).bounds(x + 70, y + 117, 60, 16).build();
        clear.setTooltip(Tooltip.create(RemoldText.tr("editor.clear.hint")));
        addRenderableWidget(clear);
        Button items = Button.builder(RemoldText.tr("editor.items"), b -> minecraft.setScreen(new ItemPickerScreen(this, stack -> cursor = stack)))
                .bounds(x + 132, y + 117, 60, 16).build();
        items.setTooltip(Tooltip.create(RemoldText.tr("editor.items.hint")));
        addRenderableWidget(items);

        Button data = Button.builder(RemoldText.tr("editor.item_data"), b -> openItemData())
                .bounds(x + 8, y + 135, 60, 16).build();
        data.setTooltip(Tooltip.create(RemoldText.tr("editor.item_data.hint").append(Component.literal("\n"))
                .append(RemoldText.tr("editor.item_data.example").withStyle(ChatFormatting.GRAY))));
        addRenderableWidget(data);
        Button tooltip = Button.builder(RemoldText.tr("editor.tooltip"), b -> openAnnotation())
                .bounds(x + 70, y + 135, 60, 16).build();
        tooltip.setTooltip(Tooltip.create(RemoldText.tr("editor.tooltip.hint")));
        addRenderableWidget(tooltip);
        Button browse = Button.builder(RemoldText.tr("editor.recipes"), b -> openBrowser())
                .bounds(x + 132, y + 135, 60, 16).build();
        browse.setTooltip(Tooltip.create(RemoldText.tr("editor.recipes.hint")));
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
            default -> {
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

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            UiDraw.slot(g, leftPos + slot.x - 1, topPos + slot.y - 1, 18);
        }
        Machine.Kind kind = state.machine.kind();
        if (state.machine.isTemplate() && state.template == null) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(RemoldText.message(
                    state.templateError == null ? RemoldText.error("no_template") : state.templateError), imageWidth - 16);
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
                if (ghost.stack().getCount() > 1) {
                    g.renderItemDecorations(font, ghost.display().copyWithCount(ghost.stack().getCount()), ix, iy);
                }
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
                g.drawString(font, RemoldText.tr("editor.more", state.inputs.length - MAX_TEMPLATE_INPUTS), leftPos + 8, topPos + 92, 0xFF404040, false);
            }
            if (state.outputs.length > MAX_TEMPLATE_OUTPUTS) {
                g.drawString(font, "+" + (state.outputs.length - MAX_TEMPLATE_OUTPUTS), leftPos + 150, topPos + 74, 0xFF404040, false);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 8, 5, 0xFF404040, false);
        int textWidth = state.checklistMod != null ? imageWidth - 104 : imageWidth - 50;
        if (state.checklistMod != null) {
            Checklist.Summary summary = Checklist.summary(state.checklistMod);
            String text = RemoldText.tr("editor.checklist", summary.done(), summary.total()).getString();
            g.drawString(font, font.plainSubstrByWidth(text, textWidth), 50, 5, 0xFF2E7D32, false);
        } else if (state.fromSource()) {
            String text = RemoldText.tr("editor.file", state.sourceName).getString();
            g.drawString(font, font.plainSubstrByWidth(text, textWidth), 50, 5, 0xFF1565C0, false);
        } else if (state.loadedFrom != null) {
            String editing = RemoldText.tr("editor.editing", state.loadedFrom.toString()).getString();
            g.drawString(font, font.plainSubstrByWidth(editing, textWidth), 50, 5, 0xFF7A5C00, false);
        }
        Machine machine = state.machine;
        if (machine.isTemplate() && machine.examples().size() > 1) {
            String label = (state.exampleIndex + 1) + "/" + machine.examples().size();
            g.drawString(font, label, 161 - font.width(label) / 2, 20, 0xFF404040, false);
        }
        if (machine.kind().cooking()) {
            g.drawString(font, RemoldText.tr("editor.time"), 150, 38, 0xFF404040, false);
            g.drawString(font, RemoldText.tr("editor.xp.short"), 150, 66, 0xFF404040, false);
        }
        if (machine.kind() == Machine.Kind.SHAPELESS) {
            g.drawString(font, RemoldText.tr("editor.any_order"), 118, 82, 0xFF707070, false);
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
            else lines.add(RemoldText.tr("editor.slot.output").withStyle(ChatFormatting.YELLOW));
            lines.add(RemoldText.tr("editor.slot.output.hint").withStyle(ChatFormatting.DARK_GRAY));
            if (!stack.isEmpty()) {
                lines.add(RemoldText.tr("editor.slot.output.scroll").withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            GhostIngredient ghost = state.inputs[slot.index()];
            if (ghost.isEmpty()) {
                lines.add(RemoldText.tr(state.machine.kind() == Machine.Kind.SMITHING
                        ? new String[]{"editor.slot.template", "editor.slot.base", "editor.slot.addition"}[slot.index()] : "editor.slot.input").withStyle(ChatFormatting.YELLOW));
                lines.add(RemoldText.tr("editor.slot.input.hint").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.addAll(getTooltipFromItem(minecraft, ghost.display()));
                if (ghost.tag() != null) {
                    lines.add(RemoldText.tr("editor.slot.tag", "#" + ghost.tag().location()).withStyle(ChatFormatting.GOLD));
                }
                if (countable(slot.index())) {
                    lines.add(RemoldText.tr("editor.slot.amount", ghost.stack().getCount()).withStyle(ChatFormatting.AQUA));
                } else if (state.machine.isTemplate()) {
                    lines.add(RemoldText.tr("editor.slot.single").withStyle(ChatFormatting.DARK_GRAY));
                }
                lines.add(RemoldText.tr("editor.slot.tag.hint").withStyle(ChatFormatting.DARK_GRAY));
                lines.add(RemoldText.tr("editor.slot.clear").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

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
            setInput(slot.index(), ghostFromCursor(slot.index()));
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
        if (slot != null && !slot.output() && countable(slot.index()) && !state.inputs[slot.index()].isEmpty()) {
            ItemStack stack = state.inputs[slot.index()].stack();
            int step = hasShiftDown() ? 10 : 1;
            stack.setCount(Math.max(1, Math.min(9999, stack.getCount() + (scrollY > 0 ? step : -step))));
            return true;
        }
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

    private boolean countable(int input) {
        return state.machine.isTemplate() && state.template != null && input < state.template.inputs.size()
                && state.template.inputs.get(input).countable();
    }

    private GhostIngredient ghostFromCursor(int input) {
        return countable(input) ? new GhostIngredient(cursor.copy(), null) : GhostIngredient.of(cursor);
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
            if (current.isEmpty() && !cursor.isEmpty()) current = ghostFromCursor(slot.index());
            setInput(slot.index(), current.cycleTag());
        } else if (!cursor.isEmpty()) {
            setInput(slot.index(), ghostFromCursor(slot.index()));
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
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Screen target = returnTo != null ? returnTo : parent;
        returnTo = null;
        minecraft.setScreen(target);
    }

    @Nullable private Screen returnTo;

    public void returnTo(@Nullable Screen screen) {
        returnTo = screen;
    }

    private void switchExample(int delta) {
        RecipeFactory.loadExample(state, state.exampleIndex + delta, registries());
        rebuildWidgets();
    }

    private void save(boolean apply) {
        DataResult<ResourceLocation> id = RecipeFactory.parseId(state.recipeId.isEmpty() && state.autoId
                ? RecipeFactory.suggestId(state, recipes()) : state.recipeId);
        if (id.error().isPresent()) {
            RemoldClient.toast(false, RemoldText.tr("notice.cant_save.title"), RemoldText.message(id.error().get().message()));
            return;
        }
        DataResult<JsonObject> json = RecipeFactory.build(state, registries());
        if (json.error().isPresent()) {
            RemoldClient.toast(false, RemoldText.tr("notice.cant_save.title"), RemoldText.message(json.error().get().message()));
            return;
        }
        ResourceLocation recipeId = id.getOrThrow();
        String recipeJson = json.getOrThrow().toString();
        boolean skipChecks = hasAltDown();
        List<Component> warnings = skipChecks ? List.of() : progressionWarnings(recipeId, json.getOrThrow());
        if (warnings.isEmpty()) {
            saveChecked(recipeId, recipeJson, apply, skipChecks);
        } else {
            minecraft.setScreen(new WarningScreen(this, warnings, () -> saveChecked(recipeId, recipeJson, apply, false)));
        }
    }

    private List<Component> progressionWarnings(ResourceLocation recipeId, JsonObject json) {
        var parsed = net.minecraft.world.item.crafting.Recipe.CODEC.parse(
                registries().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), json).result();
        if (parsed.isEmpty()) return List.of();
        var all = recipes().getRecipes();
        List<Component> warnings = new ArrayList<>(ProgressionCheck.duplicationLoops(parsed.get(), recipeId, all, registries()));
        if (recipes().byKey(recipeId).isPresent()) {
            List<net.minecraft.world.item.crafting.Recipe<?>> after = ProgressionCheck.without(all, java.util.Set.of(recipeId));
            after.add(parsed.get());
            warnings.addAll(ProgressionCheck.describeLost(ProgressionCheck.lostItems(
                    all.stream().<net.minecraft.world.item.crafting.Recipe<?>>map(RecipeHolder::value).toList(), after, java.util.Set.of(), registries()), 12));
        }
        return warnings;
    }

    private void saveChecked(ResourceLocation recipeId, String recipeJson, boolean apply, boolean skipChecks) {
        List<ResourceLocation> conflicts = ConflictCheck.find(state, recipes(), minecraft.level, recipeId);
        if (conflicts.isEmpty() || skipChecks) {
            send(recipeId, recipeJson, apply);
            return;
        }
        minecraft.setScreen(new ConflictScreen(this, conflicts,
                () -> send(recipeId, recipeJson, apply),
                () -> {
                    for (ResourceLocation other : conflicts) {
                        Services.PLATFORM.sendToServer(new Payloads.RecipeAction(Payloads.Action.REMOVE, other, false));
                    }
                    send(recipeId, recipeJson, apply);
                }));
    }

    private void send(ResourceLocation id, String json, boolean apply) {
        if (state.fromSource()) {
            Services.PLATFORM.sendToServer(new Payloads.SourceSave(state.sourceKey, state.sourceIndex, json, apply));
            dev.remold.client.Progress.setDone(dev.remold.client.Progress.fileKey(state.sourceKey, id.toString()), true);
            state.markClean();
            return;
        }
        Services.PLATFORM.sendToServer(new Payloads.SaveRecipe(id, json, apply));
        if (state.checklistMod != null && !state.output().isEmpty()) {
            dev.remold.client.Progress.setDone(dev.remold.client.Progress.itemKey(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(state.output().getItem()).toString()), true);
        }
        state.loadedFrom = id;
        state.recipeId = id.toString();
        state.autoId = false;
        if (idBox != null) {
            settingId = true;
            idBox.setValue(state.recipeId);
            settingId = false;
        }
        state.markClean();
    }

    @Nullable private Runnable pendingReplace;

    private void replaceDraft(Runnable action) {
        if (!state.isDirty()) {
            action.run();
        } else {
            pendingReplace = action;
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (pendingReplace != null && minecraft.screen == this) {
            Runnable action = pendingReplace;
            pendingReplace = null;
            minecraft.setScreen(new ConfirmScreen(discard -> {
                minecraft.setScreen(this);
                if (discard) action.run();
            }, RemoldText.tr("editor.unsaved.title"), RemoldText.tr("editor.unsaved.message"),
                    RemoldText.tr("editor.unsaved.discard"), RemoldText.tr("editor.unsaved.keep")));
        }
    }

    private int outputIndexForData() {
        if (selectedOutput < state.outputs.length && !state.outputs[selectedOutput].isEmpty()) return selectedOutput;
        for (int i = 0; i < state.outputs.length; i++) if (!state.outputs[i].isEmpty()) return i;
        return -1;
    }

    private void openItemData() {
        int index = outputIndexForData();
        if (index < 0) {
            RemoldClient.toast(false, RemoldText.tr("notice.no_output.title"), RemoldText.tr("notice.no_output"));
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

    public void loadFromSource(String key, int index, String name, ResourceLocation id, com.google.gson.JsonObject json) {
        var parsed = net.minecraft.world.item.crafting.Recipe.CODEC.parse(
                registries().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), json);
        if (parsed.error().isPresent()) {
            RemoldClient.toast(false, RemoldText.tr("files.cant_open"), RemoldText.tr("files.cant_open.message"));
            return;
        }
        dev.remold.core.TemplateCache.preload(id, json);
        RecipeHolder<?> holder = new RecipeHolder<>(id, parsed.getOrThrow());
        replaceDraft(() -> {
            RecipeFactory.load(state, holder, recipes(), registries());
            state.sourceKey = key;
            state.sourceIndex = index;
            state.sourceName = name;
            state.checklistMod = null;
            cursor = ItemStack.EMPTY;
            selectedOutput = 0;
            state.markClean();
            if (showing()) rebuildWidgets();
        });
    }

    public void startChecklistItem(String mod, net.minecraft.world.item.Item item) {
        replaceDraft(() -> {
            RecipeHolder<?> existing = Checklist.remoldRecipeFor(item);
            if (existing != null) {
                RecipeFactory.load(state, existing, recipes(), registries());
            } else {
                Machine machine = state.machine;
                state.clear();
                if (machine.isTemplate()) RecipeFactory.setMachine(state, Machines.SHAPED, registries());
                state.outputs[0] = new ItemStack(item);
                updateAutoId();
            }
            state.forgetSource();
            state.checklistMod = mod;
            cursor = ItemStack.EMPTY;
            selectedOutput = 0;
            state.markClean();
            if (showing()) rebuildWidgets();
        });
    }

    public void load(RecipeHolder<?> holder) {
        replaceDraft(() -> {
            RecipeFactory.load(state, holder, recipes(), registries());
            state.forgetSource();
            state.checklistMod = null;
            cursor = ItemStack.EMPTY;
            selectedOutput = 0;
            state.markClean();
            if (showing()) rebuildWidgets();
        });
    }

    public void onTemplateReceived(ResourceLocation id) {
        Machine machine = state.machine;
        if (machine.isTemplate() && state.template == null && !machine.examples().isEmpty()
                && machine.examples().get(Math.floorMod(state.exampleIndex, machine.examples().size())).id().equals(id)) {
            boolean wasClean = !state.isDirty();
            RecipeFactory.loadExample(state, state.exampleIndex, registries());
            if (wasClean) state.markClean();
            rebuildWidgets();
        }
    }

    public void loadFromViewer(@Nullable ResourceLocation recipeId, List<ItemStack> inputs, ItemStack output) {
        var holder = recipeId == null ? java.util.Optional.<RecipeHolder<?>>empty() : recipes().byKey(recipeId);
        if (holder.isPresent()) load(holder.get());
        else loadItems(inputs, output, false);
    }

    public void loadItems(List<ItemStack> inputs, ItemStack output, boolean shaped) {
        replaceDraft(() -> {
            RecipeFactory.loadItems(state, inputs, output, shaped, registries());
            updateAutoId();
            state.markClean();
            if (showing()) rebuildWidgets();
        });
    }

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

    public void pickUp(ItemStack stack) {
        cursor = stack.copy();
    }
}
