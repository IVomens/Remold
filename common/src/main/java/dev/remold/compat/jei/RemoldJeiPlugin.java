package dev.remold.compat.jei;

import dev.remold.Remold;
import dev.remold.client.ClientState;
import dev.remold.client.screen.EditorMenu;
import dev.remold.client.screen.EditorScreen;
import dev.remold.compat.ViewerBridge;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI integration:
 * <ul>
 *     <li>drag items (and tags) from JEI's item list onto the editor's slots</li>
 *     <li>the "+" button on ANY recipe in JEI loads it into the editor</li>
 *     <li>R / U over editor slots show recipes / uses</li>
 *     <li>machine icons come from JEI's catalysts</li>
 *     <li>items marked "hide from viewers" disappear from JEI live</li>
 * </ul>
 */
@JeiPlugin
public class RemoldJeiPlugin implements IModPlugin {
    @Nullable private static IJeiRuntime runtime;
    private static final List<ItemStack> hidden = new ArrayList<>();
    private static boolean listenersRegistered;

    @Override
    public ResourceLocation getPluginUid() {
        return Remold.id("jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(EditorScreen.class, new GhostHandler());
        registration.addGuiContainerHandler(EditorScreen.class, new IGuiContainerHandler<>() {
            @Override
            public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(IClickableIngredientFactory factory, EditorScreen screen, double mouseX, double mouseY) {
                ItemStack stack = screen.ghostStackAt(mouseX, mouseY);
                Rect2i area = screen.ghostAreaAt(mouseX, mouseY);
                if (stack.isEmpty() || area == null) return Optional.empty();
                return factory.createBuilder(stack).buildWithArea(area);
            }
        });
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addUniversalRecipeTransferHandler(new TransferHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        hidden.clear();
        if (!listenersRegistered) {
            listenersRegistered = true;
            ViewerBridge.addCatalystLookup(RemoldJeiPlugin::catalyst);
            ViewerBridge.onAnnotationsChanged(RemoldJeiPlugin::updateHidden);
        }
        updateHidden();
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        hidden.clear();
    }

    private static ItemStack catalyst(ResourceLocation recipeType) {
        if (runtime == null) return ItemStack.EMPTY;
        var manager = runtime.getRecipeManager();
        return manager.getRecipeType(recipeType)
                .flatMap(type -> manager.createRecipeCatalystLookup(type).getItemStack().findFirst())
                .orElse(ItemStack.EMPTY);
    }

    private static void updateHidden() {
        IJeiRuntime rt = runtime;
        if (rt == null) return;
        var ingredients = rt.getIngredientManager();
        if (!hidden.isEmpty()) {
            ingredients.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, List.copyOf(hidden));
            hidden.clear();
        }
        ClientState.annotations().forEach((id, annotation) -> {
            if (annotation.hideFromViewers()) {
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> hidden.add(new ItemStack(item)));
            }
        });
        if (!hidden.isEmpty()) ingredients.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, List.copyOf(hidden));
    }

    static class GhostHandler implements IGhostIngredientHandler<EditorScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(EditorScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
            Optional<ItemStack> stack = ingredient.getItemStack();
            if (stack.isEmpty()) return List.of();
            List<Target<I>> targets = new ArrayList<>();
            for (EditorScreen.DropTarget drop : screen.dropTargets()) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return drop.area();
                    }

                    @Override
                    public void accept(I ignored) {
                        drop.accept().accept(stack.get());
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {
        }
    }

    static class TransferHandler implements IUniversalRecipeTransferHandler<EditorMenu> {
        @Override
        public Class<? extends EditorMenu> getContainerClass() {
            return EditorMenu.class;
        }

        @Override
        public Optional<MenuType<EditorMenu>> getMenuType() {
            return Optional.empty();
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(EditorMenu menu, Object recipe, IRecipeSlotsView slots, Player player, boolean maxTransfer, boolean doTransfer) {
            if (!doTransfer) return null;
            EditorScreen screen = menu.screen;
            if (screen == null) return null;
            if (recipe instanceof RecipeHolder<?> holder) {
                screen.load(holder);
                return null;
            }
            // Not a real recipe (e.g. JEI's anvil or brewing displays): copy the items over.
            List<ItemStack> inputs = new ArrayList<>();
            for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
                inputs.add(slot.getDisplayedItemStack().orElse(ItemStack.EMPTY));
            }
            ItemStack output = slots.getSlotViews(RecipeIngredientRole.OUTPUT).stream()
                    .flatMap(s -> s.getDisplayedItemStack().stream()).findFirst().orElse(ItemStack.EMPTY);
            screen.loadItems(inputs, output, false);
            return null;
        }
    }
}
