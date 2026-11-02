package dev.remold.core;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

/**
 * Something that can process a recipe: a crafting table, a furnace, or any machine from any mod.
 *
 * @param id       the recipe type id (e.g. {@code minecraft:smelting}, {@code create:mixing})
 * @param kind     which editor layout to use
 * @param examples for {@link Kind#TEMPLATE} machines: existing recipes used as templates
 */
public record Machine(ResourceLocation id, Kind kind, Component name, ItemStack icon, List<RecipeHolder<?>> examples) {

    public enum Kind {
        SHAPED(9, true), SHAPELESS(9, true),
        SMELTING(1, false), BLASTING(1, false), SMOKING(1, false), CAMPFIRE(1, false),
        STONECUTTING(1, false), SMITHING(3, false),
        /** Any other recipe type: edit a copy of an existing recipe. */
        TEMPLATE(0, false);

        public final int inputs;
        public final boolean grid;

        Kind(int inputs, boolean grid) {
            this.inputs = inputs;
            this.grid = grid;
        }

        public boolean cooking() {
            return this == SMELTING || this == BLASTING || this == SMOKING || this == CAMPFIRE;
        }

        public int defaultCookTime() {
            return switch (this) {
                case BLASTING, SMOKING -> 100;
                case CAMPFIRE -> 600;
                default -> 200;
            };
        }
    }

    public boolean isTemplate() {
        return kind == Kind.TEMPLATE;
    }
}
