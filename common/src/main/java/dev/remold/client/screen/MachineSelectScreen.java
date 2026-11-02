package dev.remold.client.screen;

import dev.remold.RemoldText;
import dev.remold.core.Machine;
import dev.remold.core.Machines;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Pick a machine: vanilla workstations plus every recipe type registered by any mod. */
public class MachineSelectScreen extends Screen {
    private final Screen parent;
    private final Consumer<Machine> onPick;
    private List<Machine> machines = List.of();
    private EditBox search;
    private MachineList list;

    public MachineSelectScreen(Screen parent, Consumer<Machine> onPick) {
        super(RemoldText.tr("machines.title"));
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        machines = Machines.all(minecraft.level.getRecipeManager(), minecraft.level.registryAccess());
        search = new EditBox(font, width / 2 - 100, 22, 200, 16, RemoldText.tr("search"));
        search.setHint(RemoldText.tr("machines.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setResponder(s -> refresh());
        addRenderableWidget(search);
        list = new MachineList(minecraft, width, height - 76, 44, 24);
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 - 50, height - 28, 100, 20).build());
        refresh();
        setInitialFocus(search);
    }

    private void refresh() {
        String q = search.getValue().toLowerCase(Locale.ROOT);
        list.set(machines.stream().filter(m -> q.isEmpty()
                || m.name().getString().toLowerCase(Locale.ROOT).contains(q)
                || m.id().toString().contains(q)).toList());
    }

    private void pick(Machine machine) {
        onPick.accept(machine);
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    class MachineList extends ObjectSelectionList<MachineList.Entry> {
        MachineList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void set(List<Machine> machines) {
            replaceEntries(machines.stream().map(Entry::new).toList());
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return 300;
        }

        class Entry extends ObjectSelectionList.Entry<Entry> {
            final Machine machine;

            Entry(Machine machine) {
                this.machine = machine;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
                g.renderItem(machine.icon(), left + 2, top + 2);
                g.drawString(font, machine.name(), left + 24, top + 2, 0xFFFFFF);
                String sub = machine.isTemplate()
                        ? RemoldText.tr("machines.recipes", machine.id().toString(), machine.examples().size()).getString()
                        : machine.id().toString();
                g.drawString(font, sub, left + 24, top + 12, 0x808080);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                pick(machine);
                return true;
            }

            @Override
            public Component getNarration() {
                return machine.name();
            }
        }
    }
}
