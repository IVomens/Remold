package dev.remold.core;

import dev.remold.compat.ViewerBridge;
import dev.remold.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Machines {
    private Machines() {}

    public static final Machine SHAPED = builtin("crafting", Machine.Kind.SHAPED, "Crafting Table (shaped)", Items.CRAFTING_TABLE);
    public static final Machine SHAPELESS = builtin("crafting", Machine.Kind.SHAPELESS, "Crafting Table (shapeless)", Items.CRAFTING_TABLE);
    public static final Machine SMELTING = builtin("smelting", Machine.Kind.SMELTING, "Furnace", Items.FURNACE);
    public static final Machine BLASTING = builtin("blasting", Machine.Kind.BLASTING, "Blast Furnace", Items.BLAST_FURNACE);
    public static final Machine SMOKING = builtin("smoking", Machine.Kind.SMOKING, "Smoker", Items.SMOKER);
    public static final Machine CAMPFIRE = builtin("campfire_cooking", Machine.Kind.CAMPFIRE, "Campfire", Items.CAMPFIRE);
    public static final Machine STONECUTTING = builtin("stonecutting", Machine.Kind.STONECUTTING, "Stonecutter", Items.STONECUTTER);
    public static final Machine SMITHING = builtin("smithing", Machine.Kind.SMITHING, "Smithing Table", Items.SMITHING_TABLE);

    public static final List<Machine> BUILTIN = List.of(SHAPED, SHAPELESS, SMELTING, BLASTING, SMOKING, CAMPFIRE, STONECUTTING, SMITHING);

    private static Machine builtin(String type, Machine.Kind kind, String name, ItemLike icon) {
        return new Machine(ResourceLocation.withDefaultNamespace(type), kind, Component.literal(name), new ItemStack(icon), List.of());
    }

    public static List<Machine> all(RecipeManager recipes, RegistryAccess registries) {
        List<Machine> result = new ArrayList<>(BUILTIN);
        Map<RecipeType<?>, List<RecipeHolder<?>>> byType = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            byType.computeIfAbsent(holder.value().getType(), t -> new ArrayList<>()).add(holder);
        }
        List<Machine> modded = new ArrayList<>();
        byType.forEach((type, holders) -> {
            ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
            if (id == null || id.getNamespace().equals("minecraft")) return;
            modded.add(template(id, holders, registries));
        });
        modded.sort(Comparator.comparing(m -> m.name().getString()));
        result.addAll(modded);
        return result;
    }

    public static Machine template(ResourceLocation typeId, List<RecipeHolder<?>> examples, RegistryAccess registries) {
        List<RecipeHolder<?>> sorted = new ArrayList<>(examples);
        sorted.sort(Comparator.comparing(h -> h.id().toString()));
        return new Machine(typeId, Machine.Kind.TEMPLATE, templateName(typeId), icon(typeId, sorted, registries), List.copyOf(sorted));
    }

    public static Component templateName(ResourceLocation typeId) {
        return Component.literal(Services.PLATFORM.modName(typeId.getNamespace()) + ": " + pretty(typeId.getPath()));
    }

    static String pretty(String path) {
        String[] words = path.replace('/', ' ').replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return sb.toString();
    }

    private static ItemStack icon(ResourceLocation typeId, List<RecipeHolder<?>> examples, RegistryAccess registries) {
        ItemStack fromViewer = ViewerBridge.catalyst(typeId);
        if (!fromViewer.isEmpty()) return fromViewer;

        var sameName = BuiltInRegistries.ITEM.getOptional(typeId);
        if (sameName.isPresent()) return new ItemStack(sameName.get());
        for (RecipeHolder<?> h : examples) {
            ItemStack result = h.value().getResultItem(registries);
            if (!result.isEmpty()) return result.copyWithCount(1);
        }
        return new ItemStack(Items.BARREL);
    }

    public static Machine forRecipe(RecipeHolder<?> holder, RecipeManager recipes, RegistryAccess registries) {
        RecipeSerializer<?> s = holder.value().getSerializer();
        if (s == RecipeSerializer.SHAPED_RECIPE) return SHAPED;
        if (s == RecipeSerializer.SHAPELESS_RECIPE) return SHAPELESS;
        if (s == RecipeSerializer.SMELTING_RECIPE) return SMELTING;
        if (s == RecipeSerializer.BLASTING_RECIPE) return BLASTING;
        if (s == RecipeSerializer.SMOKING_RECIPE) return SMOKING;
        if (s == RecipeSerializer.CAMPFIRE_COOKING_RECIPE) return CAMPFIRE;
        if (s == RecipeSerializer.STONECUTTER) return STONECUTTING;
        if (s == RecipeSerializer.SMITHING_TRANSFORM) return SMITHING;
        RecipeType<?> type = holder.value().getType();
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        List<RecipeHolder<?>> examples = new ArrayList<>();
        for (RecipeHolder<?> h : recipes.getRecipes()) {
            if (h.value().getType() == type && h.value().getSerializer() == s) examples.add(h);
        }
        if (!examples.contains(holder)) examples.add(holder);
        return template(typeId, examples, registries);
    }
}
