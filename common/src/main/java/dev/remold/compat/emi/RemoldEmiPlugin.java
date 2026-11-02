package dev.remold.compat.emi;

import dev.remold.client.ClientState;
import dev.remold.client.screen.EditorMenu;
import dev.remold.client.screen.EditorScreen;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import java.util.List;
import dev.remold.compat.ViewerBridge;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

@EmiEntrypoint
public class RemoldEmiPlugin implements EmiPlugin {
    private static boolean lookupRegistered;

    @Override
    public void register(EmiRegistry registry) {
        if (!lookupRegistered) {
            lookupRegistered = true;
            ViewerBridge.addCatalystLookup(RemoldEmiPlugin::workstation);
        }

        registry.addDragDropHandler(EditorScreen.class, (screen, ingredient, x, y) -> {
            ItemStack stack = first(ingredient);
            if (stack.isEmpty()) return false;
            for (EditorScreen.DropTarget target : screen.dropTargets()) {
                if (target.area().contains(x, y)) {
                    target.accept().accept(stack);
                    return true;
                }
            }
            return false;
        });

        registry.addStackProvider(EditorScreen.class, (screen, x, y) -> {
            ItemStack stack = screen.ghostStackAt(x, y);
            return stack.isEmpty() ? EmiStackInteraction.EMPTY : new EmiStackInteraction(EmiStack.of(stack));
        });

        registry.addRecipeHandler(EditorMenu.TYPE, new EmiRecipeHandler<>() {
            @Override
            public EmiPlayerInventory getInventory(AbstractContainerScreen<EditorMenu> screen) {
                return new EmiPlayerInventory(List.of());
            }

            @Override
            public boolean supportsRecipe(EmiRecipe recipe) {
                return true;
            }

            @Override
            public boolean canCraft(EmiRecipe recipe, EmiCraftContext<EditorMenu> context) {
                return true;
            }

            @Override
            public boolean craft(EmiRecipe recipe, EmiCraftContext<EditorMenu> context) {
                if (!(context.getScreen() instanceof EditorScreen editor)) return false;
                var backing = recipe.getBackingRecipe();
                List<ItemStack> inputs = recipe.getInputs().stream().map(RemoldEmiPlugin::first).toList();
                ItemStack output = recipe.getOutputs().stream().map(EmiStack::getItemStack).filter(s -> !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
                editor.loadFromViewer(backing != null ? backing.id() : recipe.getId(), inputs, output);
                Minecraft.getInstance().setScreen(editor);
                return true;
            }
        });

        registry.addRecipeDecorator((recipe, widgets) -> {
            var backing = recipe.getBackingRecipe();
            var id = backing != null ? backing.id() : recipe.getId();
            if (id == null) return;
            String requirement = ClientState.gate(id);
            if (requirement == null) return;
            boolean locked = !ClientState.isUnlocked(requirement);
            int x = widgets.getWidth() - 10;
            widgets.addDrawable(x, 0, 9, 10, (g, mouseX, mouseY, delta) -> dev.remold.client.widget.UiDraw.padlock(g, 0, 0, locked));
            widgets.addTooltipText(dev.remold.client.RemoldClient.lockTooltip(id), x, 0, 9, 10);
        });

        registry.removeEmiStacks(stack -> {
            ItemStack item = stack.getItemStack();
            if (item.isEmpty()) return false;
            var annotation = ClientState.annotations().get(BuiltInRegistries.ITEM.getKey(item.getItem()));
            return annotation != null && annotation.hideFromViewers();
        });
    }

    private static ItemStack first(EmiIngredient ingredient) {
        for (EmiStack stack : ingredient.getEmiStacks()) {
            ItemStack item = stack.getItemStack();
            if (!item.isEmpty()) return item.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack workstation(ResourceLocation recipeType) {
        var manager = EmiApi.getRecipeManager();
        for (var category : manager.getCategories()) {
            if (category.getId().equals(recipeType)) {
                for (EmiIngredient workstation : manager.getWorkstations(category)) {
                    ItemStack stack = first(workstation);
                    if (!stack.isEmpty()) return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }
}
