package dev.remold.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class ConflictCheck {
    private ConflictCheck() {}

    public static List<ResourceLocation> find(EditorState state, RecipeManager recipes, Level level, @Nullable ResourceLocation ownId) {
        List<? extends RecipeHolder<?>> matches = switch (state.machine.kind()) {
            case SHAPED, SHAPELESS -> {
                List<ItemStack> grid = new ArrayList<>();
                for (GhostIngredient g : state.inputs) grid.add(g.isEmpty() ? ItemStack.EMPTY : g.stack().copyWithCount(1));
                if (grid.stream().allMatch(ItemStack::isEmpty)) yield List.of();
                yield recipes.getRecipesFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, grid), level);
            }
            case SMELTING -> recipes.getRecipesFor(RecipeType.SMELTING, single(state), level);
            case BLASTING -> recipes.getRecipesFor(RecipeType.BLASTING, single(state), level);
            case SMOKING -> recipes.getRecipesFor(RecipeType.SMOKING, single(state), level);
            case CAMPFIRE -> recipes.getRecipesFor(RecipeType.CAMPFIRE_COOKING, single(state), level);
            case SMITHING -> recipes.getRecipesFor(RecipeType.SMITHING,
                    new SmithingRecipeInput(stack(state, 0), stack(state, 1), stack(state, 2)), level);
            default -> List.of();
        };
        List<ResourceLocation> ids = new ArrayList<>();
        for (RecipeHolder<?> holder : matches) {
            if (!holder.id().equals(ownId)) ids.add(holder.id());
        }
        return ids;
    }

    private static ItemStack stack(EditorState state, int index) {
        GhostIngredient g = state.inputs[index];
        return g.isEmpty() ? ItemStack.EMPTY : g.stack().copyWithCount(1);
    }

    private static SingleRecipeInput single(EditorState state) {
        return new SingleRecipeInput(stack(state, 0));
    }
}
