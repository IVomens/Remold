package dev.remold.core;

import dev.remold.RemoldText;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProgressionCheck {
    private ProgressionCheck() {}

    private record Node(Recipe<?> recipe, Item output, List<Ingredient> ingredients) {}

    private static List<Ingredient> nonEmpty(Recipe<?> recipe) {
        try {
            return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ItemStack output(Recipe<?> recipe, HolderLookup.Provider registries) {
        try {
            return recipe.getResultItem(registries);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static Set<Item> produced(Collection<? extends Recipe<?>> recipes, HolderLookup.Provider registries) {
        Set<Item> items = new HashSet<>();
        for (Recipe<?> r : recipes) {
            ItemStack out = output(r, registries);
            if (!out.isEmpty()) items.add(out.getItem());
        }
        return items;
    }

    private static Set<Item> craftable(Collection<? extends Recipe<?>> recipes, Set<Item> natural, HolderLookup.Provider registries) {
        List<Node> nodes = new ArrayList<>();
        for (Recipe<?> r : recipes) {
            ItemStack out = output(r, registries);
            List<Ingredient> ingredients = nonEmpty(r);

            if (out.isEmpty() || ingredients.isEmpty()) continue;
            nodes.add(new Node(r, out.getItem(), ingredients));
        }
        Map<Item, List<int[]>> uses = new HashMap<>();
        int[] missing = new int[nodes.size()];
        boolean[][] satisfied = new boolean[nodes.size()][];
        for (int n = 0; n < nodes.size(); n++) {
            List<Ingredient> ingredients = nodes.get(n).ingredients();
            missing[n] = ingredients.size();
            satisfied[n] = new boolean[ingredients.size()];
            for (int j = 0; j < ingredients.size(); j++) {
                for (ItemStack stack : ingredients.get(j).getItems()) {
                    uses.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(new int[]{n, j});
                }
            }
        }
        Set<Item> obtained = new HashSet<>(natural);
        ArrayDeque<Item> queue = new ArrayDeque<>(natural);
        while (!queue.isEmpty()) {
            for (int[] use : uses.getOrDefault(queue.poll(), List.of())) {
                int n = use[0], j = use[1];
                if (satisfied[n][j]) continue;
                satisfied[n][j] = true;
                if (--missing[n] == 0 && obtained.add(nodes.get(n).output())) {
                    queue.add(nodes.get(n).output());
                }
            }
        }
        return obtained;
    }

    private static Object cachedFor;
    private static int cachedSize = -1;
    private static Set<Item> cachedNatural;
    private static Set<Item> cachedWas;

    public static List<Item> lostItemsFrom(Collection<RecipeHolder<?>> all, Collection<? extends Recipe<?>> after,
                                           Set<Item> ignore, HolderLookup.Provider registries) {
        if (cachedFor != all || cachedSize != all.size()) {
            List<Recipe<?>> before = new ArrayList<>(all.size());
            for (RecipeHolder<?> h : all) before.add(h.value());
            Set<Item> natural = new HashSet<>();
            Set<Item> made = produced(before, registries);
            for (Item item : BuiltInRegistries.ITEM) if (!made.contains(item)) natural.add(item);
            cachedNatural = natural;
            cachedWas = craftable(before, natural, registries);
            cachedFor = all;
            cachedSize = all.size();
        }
        Set<Item> now = craftable(after, cachedNatural, registries);
        List<Item> lost = new ArrayList<>();
        for (Item item : cachedWas) if (!now.contains(item) && !ignore.contains(item)) lost.add(item);
        lost.sort(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()));
        return lost;
    }

    public static List<Recipe<?>> without(Collection<RecipeHolder<?>> all, Set<ResourceLocation> removed) {
        List<Recipe<?>> list = new ArrayList<>();
        for (RecipeHolder<?> h : all) if (!removed.contains(h.id())) list.add(h.value());
        return list;
    }

    public static List<Component> duplicationLoops(Recipe<?> recipe, ResourceLocation ownId, Collection<RecipeHolder<?>> all,
                                                   HolderLookup.Provider registries) {
        List<Component> warnings = new ArrayList<>();
        List<Ingredient> ins = nonEmpty(recipe);
        ItemStack out = output(recipe, registries);
        if (ins.isEmpty() || out.isEmpty()) return warnings;
        Set<Item> candidates = new LinkedHashSet<>();
        for (ItemStack s : ins.getFirst().getItems()) {
            if (candidates.size() >= 16) break;
            candidates.add(s.getItem());
        }
        for (Item a : candidates) {
            ItemStack aStack = new ItemStack(a);
            if (!ins.stream().allMatch(i -> i.test(aStack))) continue;
            int aCount = ins.size(), bCount = out.getCount();
            Item b = out.getItem();
            if (a == b && bCount > aCount) {
                warnings.add(RemoldText.tr("check.self_loop", aCount, name(a), bCount));
                continue;
            }
            ItemStack bStack = new ItemStack(b);
            for (RecipeHolder<?> other : all) {
                if (other.id().equals(ownId)) continue;
                ItemStack back = output(other.value(), registries);
                if (back.isEmpty() || back.getItem() != a) continue;
                List<Ingredient> otherIns = nonEmpty(other.value());
                if (otherIns.isEmpty() || !otherIns.stream().allMatch(i -> i.test(bStack))) continue;
                int cCount = otherIns.size(), dCount = back.getCount();
                if ((long) bCount * dCount > (long) aCount * cCount) {
                    warnings.add(RemoldText.tr("check.loop", aCount, name(a), bCount, name(b), other.id().toString(), cCount, dCount));
                }
            }
        }
        return warnings;
    }

    private static Component name(Item item) {
        return item.getDescription();
    }

    public static List<Component> describeLost(List<Item> lost, int max) {
        List<Component> lines = new ArrayList<>();
        if (lost.isEmpty()) return lines;
        lines.add(RemoldText.tr("check.lost", lost.size()));
        for (int i = 0; i < Math.min(max, lost.size()); i++) {
            lines.add(Component.literal(" - ").append(lost.get(i).getDescription())
                    .append(Component.literal(" (" + BuiltInRegistries.ITEM.getKey(lost.get(i)) + ")").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)));
        }
        if (lost.size() > max) lines.add(RemoldText.tr("editor.more", lost.size() - max));
        return lines;
    }
}
