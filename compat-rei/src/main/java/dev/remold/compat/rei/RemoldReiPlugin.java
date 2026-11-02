package dev.remold.compat.rei;

import dev.remold.client.ClientState;
import dev.remold.client.screen.EditorScreen;
import dev.remold.compat.ViewerBridge;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.drag.DraggableStack;
import me.shedaniel.rei.api.client.gui.drag.DraggableStackVisitor;
import me.shedaniel.rei.api.client.gui.drag.DraggedAcceptorResult;
import me.shedaniel.rei.api.client.gui.drag.DraggingContext;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import java.util.List;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.stream.Stream;

/**
 * REI integration: drag items from REI onto the editor, machine icons from REI workstations, and
 * hiding items marked "hide from viewers" (applied whenever REI reloads, e.g. after Remold applies changes).
 * <p>
 * Compiled by each loader module (REI's API is published per loader). Fabric finds it through the
 * {@code rei_client} entrypoint; NeoForge through an annotated subclass.
 */
public class RemoldReiPlugin implements REIClientPlugin {
    private static boolean lookupRegistered;

    @Override
    public void registerScreens(ScreenRegistry registry) {
        if (!lookupRegistered) {
            lookupRegistered = true;
            ViewerBridge.addCatalystLookup(RemoldReiPlugin::workstation);
        }
        registry.registerDraggableStackVisitor(new DraggableStackVisitor<EditorScreen>() {
            @Override
            public <R extends Screen> boolean isHandingScreen(R screen) {
                return screen instanceof EditorScreen;
            }

            @Override
            public DraggedAcceptorResult acceptDraggedStack(DraggingContext<EditorScreen> context, DraggableStack stack) {
                ItemStack item = itemOf(stack.getStack());
                var pos = context.getCurrentPosition();
                if (item.isEmpty() || pos == null) return DraggedAcceptorResult.PASS;
                for (EditorScreen.DropTarget target : context.getScreen().dropTargets()) {
                    if (target.area().contains(pos.x, pos.y)) {
                        target.accept().accept(item);
                        return DraggedAcceptorResult.CONSUMED;
                    }
                }
                return DraggedAcceptorResult.PASS;
            }

            @Override
            public Stream<DraggableStackVisitor.BoundsProvider> getDraggableAcceptingBounds(
                    DraggingContext<EditorScreen> context, DraggableStack stack) {
                if (itemOf(stack.getStack()).isEmpty()) return Stream.empty();
                return context.getScreen().dropTargets().stream().map(t -> {
                    Rect2i a = t.area();
                    return DraggableStackVisitor.BoundsProvider
                            .ofRectangle(new Rectangle(a.getX(), a.getY(), a.getWidth(), a.getHeight()));
                });
            }
        });
    }

    /** "+" button on any REI recipe: load it into the editor. */
    @Override
    public void registerTransferHandlers(TransferHandlerRegistry registry) {
        registry.register(context -> {
            if (!(context.getContainerScreen() instanceof EditorScreen editor)) return TransferHandler.Result.createNotApplicable();
            if (context.isActuallyCrafting()) {
                var display = context.getDisplay();
                List<ItemStack> inputs = display.getInputEntries().stream()
                        .map(ingredient -> ingredient.stream().map(RemoldReiPlugin::itemOf).filter(s -> !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY))
                        .toList();
                ItemStack output = display.getOutputEntries().stream().flatMap(i -> i.stream()).map(RemoldReiPlugin::itemOf)
                        .filter(s -> !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
                editor.loadFromViewer(display.getDisplayLocation().orElse(null), inputs, output);
                context.getMinecraft().setScreen(editor);
            }
            return TransferHandler.Result.createSuccessful().blocksFurtherHandling();
        });
    }

    @Override
    public void registerEntries(EntryRegistry registry) {
        registry.removeEntryIf(entry -> {
            ItemStack item = itemOf(entry);
            if (item.isEmpty()) return false;
            var annotation = ClientState.annotations().get(BuiltInRegistries.ITEM.getKey(item.getItem()));
            return annotation != null && annotation.hideFromViewers();
        });
    }

    private static ItemStack itemOf(EntryStack<?> stack) {
        if (stack.getType() != VanillaEntryTypes.ITEM) return ItemStack.EMPTY;
        return ((ItemStack) stack.getValue()).copyWithCount(1);
    }

    private static ItemStack workstation(ResourceLocation recipeType) {
        return CategoryRegistry.getInstance().tryGet(CategoryIdentifier.of(recipeType))
                .flatMap(config -> config.getWorkstations().stream()
                        .flatMap(ingredient -> ingredient.stream())
                        .map(RemoldReiPlugin::itemOf)
                        .filter(s -> !s.isEmpty())
                        .findFirst())
                .orElse(ItemStack.EMPTY);
    }
}
