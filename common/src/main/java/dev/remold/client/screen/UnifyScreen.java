package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.client.ClientState;
import dev.remold.client.widget.UiDraw;
import dev.remold.core.RecipeUsage;
import dev.remold.data.UnifySettings;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds materials that several mods add (more than one item in a common tag like
 * {@code #c:ingots/copper}) and unifies them on one preferred item.
 */
public class UnifyScreen extends Screen {
    /** Common tag folders where every item should be the same material. */
    private static final List<String> PREFIXES = List.of("ingots/", "nuggets/", "dusts/", "gems/", "plates/", "gears/",
            "rods/", "storage_blocks/", "raw_materials/", "wires/", "sheets/", "coins/");

    private final Screen parent;
    private final Map<ResourceLocation, List<Item>> groups = new LinkedHashMap<>();
    private final Map<ResourceLocation, Item> preferred = new LinkedHashMap<>();
    private final Set<ResourceLocation> included = new LinkedHashSet<>();
    @Nullable private ResourceLocation selected;
    private boolean hide, convert, tagInputs;
    private GroupList list;
    private Button run;
    private int estimate;

    public UnifyScreen(Screen parent) {
        super(RemoldText.tr("unify.title"));
        this.parent = parent;
        UnifySettings stored = ClientState.unify();
        hide = stored.hide();
        convert = stored.convert();
        tagInputs = stored.tagInputs();
        detect(stored);
    }

    private void detect(UnifySettings stored) {
        List<TagKey<Item>> tags = BuiltInRegistries.ITEM.getTagNames()
                .filter(t -> stored.groups().containsKey(t.location()) || (t.location().getNamespace().equals("c")
                        && PREFIXES.stream().anyMatch(p -> t.location().getPath().startsWith(p))))
                .sorted(Comparator.comparing(t -> t.location().toString())).toList();
        for (TagKey<Item> tag : tags) {
            List<Item> members = new ArrayList<>(new LinkedHashSet<>(BuiltInRegistries.ITEM.getTag(tag)
                    .map(set -> set.stream().map(h -> h.value()).toList()).orElse(List.of())));
            if (members.size() < 2) continue;
            groups.put(tag.location(), members);
            Item chosen = BuiltInRegistries.ITEM.getOptional(stored.groups().get(tag.location()))
                    .filter(members::contains).orElse(defaultPreferred(members));
            preferred.put(tag.location(), chosen);
            if (!stored.groups().containsKey(tag.location())) included.add(tag.location());
        }
        selected = groups.keySet().stream().findFirst().orElse(null);
    }

    /** Vanilla's item if there is one, otherwise the first mod's (alphabetically). */
    private static Item defaultPreferred(List<Item> members) {
        return members.stream().min(Comparator
                .comparing((Item i) -> !BuiltInRegistries.ITEM.getKey(i).getNamespace().equals("minecraft"))
                .thenComparing(i -> BuiltInRegistries.ITEM.getKey(i).toString())).orElseThrow();
    }

    @Override
    protected void init() {
        list = new GroupList(minecraft, width / 2 - 10, height - 106, 30, 22);
        list.setX(5);
        addRenderableWidget(list);
        int y = height - 56;
        int x0 = width / 2 - 160;
        addRenderableWidget(CycleButton.onOffBuilder(hide).create(x0, y, 104, 20, RemoldText.tr("unify.hide"), (b, v) -> hide = v))
                .setTooltip(Tooltip.create(RemoldText.tr("unify.hide.hint")));
        addRenderableWidget(CycleButton.onOffBuilder(tagInputs).create(x0 + 108, y, 104, 20, RemoldText.tr("unify.tag_inputs"), (b, v) -> {
            tagInputs = v;
            updateEstimate();
        })).setTooltip(Tooltip.create(RemoldText.tr("unify.tag_inputs.hint")));
        addRenderableWidget(CycleButton.onOffBuilder(convert).create(x0 + 216, y, 104, 20, RemoldText.tr("unify.convert"), (b, v) -> convert = v))
                .setTooltip(Tooltip.create(RemoldText.tr("unify.convert.hint")));
        run = addRenderableWidget(Button.builder(RemoldText.tr("unify.run").withStyle(ChatFormatting.GREEN), b -> run())
                .bounds(x0, height - 28, 104, 20).build());
        run.setTooltip(Tooltip.create(RemoldText.tr("unify.run.hint")));
        addRenderableWidget(Button.builder(RemoldText.tr("unify.all"), b -> {
            if (included.size() == groups.size()) included.clear();
            else included.addAll(groups.keySet());
            updateEstimate();
        }).bounds(x0 + 108, height - 28, 104, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(x0 + 216, height - 28, 104, 20).build());
        list.set();
        updateEstimate();
    }

    /** Recipes that make a non-preferred item, or (when widening inputs) use one. An upper bound. */
    private void updateEstimate() {
        Set<Item> others = new HashSet<>();
        for (ResourceLocation tag : included) {
            for (Item item : groups.get(tag)) if (item != preferred.get(tag)) others.add(item);
        }
        Set<Item> all = new HashSet<>(others);
        for (ResourceLocation tag : included) all.add(preferred.get(tag));
        var level = minecraft == null ? null : minecraft.level;
        if (level == null) return;
        var registries = level.registryAccess();
        estimate = (int) level.getRecipeManager().getRecipes().stream().filter(h ->
                RecipeUsage.produces(h.value(), others, registries) || (tagInputs && RecipeUsage.usesAny(h.value(), all))).count();
        if (run != null) run.active = !included.isEmpty();
    }

    private void run() {
        Map<ResourceLocation, ResourceLocation> chosen = new LinkedHashMap<>();
        for (ResourceLocation tag : included) chosen.put(tag, BuiltInRegistries.ITEM.getKey(preferred.get(tag)));
        Services.PLATFORM.sendToServer(new Payloads.Unify(new UnifySettings(chosen, hide, convert, tagInputs), !hasShiftDown()));
        onClose();
    }

    // ------------------------------------------------------------------ members of the selected group

    private int gridX() {
        return width / 2 + 5;
    }

    private int gridY() {
        return 50;
    }

    private int cols() {
        return Math.max(1, (width - gridX() - 10) / 22);
    }

    private int memberAt(double mouseX, double mouseY) {
        if (selected == null || mouseX < gridX() || mouseY < gridY()) return -1;
        int c = (int) ((mouseX - gridX()) / 22), r = (int) ((mouseY - gridY()) / 22);
        if (c >= cols()) return -1;
        int i = r * cols() + c;
        return i < groups.get(selected).size() ? i : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int i = memberAt(mouseX, mouseY);
        if (i >= 0) {
            preferred.put(selected, groups.get(selected).get(i));
            included.add(selected);
            updateEstimate();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        if (groups.isEmpty()) {
            g.drawCenteredString(font, RemoldText.tr("unify.none").withStyle(ChatFormatting.GRAY), width / 2, 60, 0xFFFFFF);
            return;
        }
        g.drawString(font, RemoldText.tr("unify.estimate", included.size(), estimate), width / 2 - 160, height - 70, 0xA0A0A0);
        if (selected == null) return;
        g.drawString(font, font.plainSubstrByWidth("#" + selected, width - gridX() - 8), gridX(), 32, 0xFFFF55);
        List<Item> members = groups.get(selected);
        Item pref = preferred.get(selected);
        for (int i = 0; i < members.size(); i++) {
            int x = gridX() + (i % cols()) * 22, y = gridY() + (i / cols()) * 22;
            UiDraw.slot(g, x, y, 20);
            g.renderItem(new ItemStack(members.get(i)), x + 2, y + 2);
            if (members.get(i) == pref) g.renderOutline(x - 1, y - 1, 22, 22, 0xFFFFD700);
        }
        int rows = (members.size() + cols() - 1) / cols();
        var hint = font.split(RemoldText.tr("unify.pick", pref.getDescription()), width - gridX() - 8);
        for (int i = 0; i < hint.size(); i++) g.drawString(font, hint.get(i), gridX(), gridY() + rows * 22 + 6 + i * 10, 0xA0A0A0);
        int hovered = memberAt(mouseX, mouseY);
        if (hovered >= 0) {
            Item item = members.get(hovered);
            List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, new ItemStack(item)));
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            lines.add(Component.literal(Services.PLATFORM.modName(id.getNamespace()) + "  (" + id + ")").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(RemoldText.tr(item == pref ? "unify.member.preferred" : "unify.member.click").withStyle(ChatFormatting.GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    class GroupList extends ObjectSelectionList<GroupList.Entry> {
        GroupList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void set() {
            replaceEntries(groups.keySet().stream().map(Entry::new).toList());
            children().stream().filter(e -> e.tag.equals(selected)).findFirst().ifPresent(this::setSelected);
        }

        @Override
        public int getRowWidth() {
            return width - 20;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final ResourceLocation tag;

            Entry(ResourceLocation tag) {
                this.tag = tag;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                boolean on = included.contains(tag);
                boolean done = ClientState.unify().groups().containsKey(tag);
                // checkbox
                g.fill(left + 2, top + 4, left + 12, top + 14, 0xFF000000);
                g.renderOutline(left + 2, top + 4, 10, 10, 0xFFA0A0A0);
                if (on) g.fill(left + 4, top + 6, left + 10, top + 12, 0xFF55FF55);
                g.renderItem(new ItemStack(preferred.get(tag)), left + 16, top + 1);
                g.drawString(font, font.plainSubstrByWidth("#" + tag, width - 40), left + 36, top + 2, done ? 0x55FF55 : 0xFFFFFF);
                g.drawString(font, RemoldText.tr(done ? "unify.group.done" : "unify.group", groups.get(tag).size()), left + 36, top + 11, 0x808080);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                // the checkbox toggles; the rest selects
                if (mouseX < getRowLeft() + 14) {
                    if (!included.remove(tag)) included.add(tag);
                    updateEstimate();
                }
                selected = tag;
                list.setSelected(this);
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal("#" + tag);
            }
        }
    }
}
