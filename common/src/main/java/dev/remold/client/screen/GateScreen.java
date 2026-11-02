package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.client.ClientState;
import dev.remold.core.RecipeUsage;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import dev.remold.server.Gates;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GateScreen extends Screen {
    enum Mode { ADVANCEMENT, STAGE, NONE }

    private static final int SUGGESTIONS = 6;

    private final Screen parent;
    private final RecipeHolder<?> recipe;
    private final ItemStack output;
    private Mode mode;
    private String value;
    private boolean allForOutput;
    private EditBox box;
    private List<AdvancementNode> suggestions = List.of();

    public GateScreen(Screen parent, RecipeHolder<?> recipe) {
        super(RemoldText.tr("gate.title"));
        this.parent = parent;
        this.recipe = recipe;
        ItemStack out;
        try {
            out = recipe.value().getResultItem(net.minecraft.client.Minecraft.getInstance().level.registryAccess());
        } catch (Exception e) {
            out = ItemStack.EMPTY;
        }
        this.output = out;
        String current = ClientState.gate(recipe.id());
        if (current == null) {
            mode = Mode.ADVANCEMENT;
            value = "";
        } else if (current.startsWith(Gates.STAGE_PREFIX)) {
            mode = Mode.STAGE;
            value = current.substring(Gates.STAGE_PREFIX.length());
        } else {
            mode = Mode.ADVANCEMENT;
            value = current;
        }
    }

    private int x0() {
        return width / 2 - 150;
    }

    @Override
    protected void init() {
        int x0 = x0();
        addRenderableWidget(CycleButton.<Mode>builder(m -> RemoldText.tr("gate.mode." + m.name().toLowerCase(Locale.ROOT)))
                .withValues(Mode.values()).withInitialValue(mode)
                .create(x0, 52, 300, 20, RemoldText.tr("gate.mode"), (b, v) -> {
                    mode = v;
                    rebuildWidgets();
                })).setTooltip(Tooltip.create(RemoldText.tr("gate.mode.hint")));
        if (mode != Mode.NONE) {
            box = new EditBox(font, x0, 78, 300, 16, RemoldText.tr("gate.value"));
            box.setMaxLength(256);
            box.setValue(value);
            box.setHint(RemoldText.tr(mode == Mode.STAGE ? "gate.stage.placeholder" : "gate.advancement.placeholder").withStyle(ChatFormatting.DARK_GRAY));
            box.setResponder(s -> {
                value = s.trim();
                updateSuggestions();
            });
            addRenderableWidget(box);
            setInitialFocus(box);
        }
        if (!output.isEmpty()) {
            addRenderableWidget(CycleButton.onOffBuilder(allForOutput)
                    .create(x0, 172, 300, 20, RemoldText.tr("gate.all", output.getHoverName()), (b, v) -> allForOutput = v))
                    .setTooltip(Tooltip.create(RemoldText.tr("gate.all.hint")));
        }
        addRenderableWidget(Button.builder(RemoldText.tr("gate.save").withStyle(ChatFormatting.GREEN), b -> save())
                .bounds(width / 2 - 102, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 2, height - 28, 100, 20).build());
        updateSuggestions();
    }

    private void updateSuggestions() {
        if (mode != Mode.ADVANCEMENT || minecraft.player == null) {
            suggestions = List.of();
            return;
        }
        String q = value.toLowerCase(Locale.ROOT);
        List<AdvancementNode> found = new ArrayList<>();
        for (AdvancementNode node : minecraft.player.connection.getAdvancements().getTree().nodes()) {
            String id = node.holder().id().toString();
            String title = node.holder().value().display().map(d -> d.getTitle().getString().toLowerCase(Locale.ROOT)).orElse("");
            if (id.equals(value)) continue;
            if (q.isEmpty() || id.contains(q) || title.contains(q)) found.add(node);
        }
        found.sort((a, b) -> a.holder().id().toString().compareTo(b.holder().id().toString()));
        suggestions = found.subList(0, Math.min(SUGGESTIONS, found.size()));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x0 = x0();
        for (int i = 0; i < suggestions.size(); i++) {
            int y = 98 + i * 11;
            if (mouseX >= x0 && mouseX < x0 + 300 && mouseY >= y && mouseY < y + 11) {
                value = suggestions.get(i).holder().id().toString();
                box.setValue(value);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void save() {
        String requirement = switch (mode) {
            case NONE -> "";
            case STAGE -> value.isEmpty() ? "" : Gates.STAGE_PREFIX + value.toLowerCase(Locale.ROOT);
            case ADVANCEMENT -> {
                ResourceLocation id = ResourceLocation.tryParse(value);
                yield id == null ? "" : id.toString();
            }
        };
        if (mode != Mode.NONE && requirement.isEmpty()) {
            dev.remold.client.RemoldClient.toast(false, RemoldText.tr("gate.title"), RemoldText.tr("gate.empty"));
            return;
        }
        Map<ResourceLocation, String> changes = new LinkedHashMap<>();
        changes.put(recipe.id(), requirement);
        if (allForOutput && !output.isEmpty()) {
            var level = minecraft.level;
            for (RecipeHolder<?> h : level.getRecipeManager().getRecipes()) {
                if (RecipeUsage.produces(h.value(), Set.of(output.getItem()), level.registryAccess())) changes.put(h.id(), requirement);
            }
        }
        Services.PLATFORM.sendToServer(new Payloads.SetGates(changes));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x0 = x0();
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        g.renderItem(output, x0, 26);
        g.drawString(font, recipe.id().toString(), x0 + 20, 30, 0xFFFF55);
        for (int i = 0; i < suggestions.size(); i++) {
            AdvancementNode node = suggestions.get(i);
            int y = 98 + i * 11;
            boolean hover = mouseX >= x0 && mouseX < x0 + 300 && mouseY >= y && mouseY < y + 11;
            Component title = node.holder().value().display().map(d -> d.getTitle().copy()).orElse(Component.empty());
            g.drawString(font, Component.literal(node.holder().id() + "  ").append(title.copy().withStyle(ChatFormatting.GRAY)),
                    x0 + 2, y + 1, hover ? 0xFFFF55 : 0xC0C0C0);
        }
        Component help = RemoldText.tr(switch (mode) {
            case ADVANCEMENT -> "gate.help.advancement";
            case STAGE -> "gate.help.stage";
            case NONE -> "gate.help.none";
        }, value.isEmpty() ? "<stage>" : value);
        int y = mode == Mode.NONE ? 80 : 100 + suggestions.size() * 11;
        for (var line : font.split(help.copy().withStyle(ChatFormatting.GRAY), 300)) {
            if (y > 162) break;
            g.drawString(font, line, x0, y, 0xFFFFFF);
            y += 10;
        }
        g.drawString(font, RemoldText.tr("gate.scope").withStyle(ChatFormatting.DARK_GRAY), x0, height - 42, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
