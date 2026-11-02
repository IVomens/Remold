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

/**
 * Catches changes that break a modpack before they are saved:
 * <ul>
 *     <li><b>Lost items</b>: items that could be crafted before the change and no longer can. Items that
 *     no recipe makes are assumed to come from the world (mining, mobs, loot); everything else must be
 *     reachable through recipes, starting from those.</li>
 *     <li><b>Duplication loops</b>: a recipe and its reverse that together create items from nothing,
 *     e.g. 9 ingots → 1 block → 10 ingots.</li>
 * </ul>
 */
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

    /** Items that some recipe in the collection makes. */
    private static Set<Item> produced(Collection<? extends Recipe<?>> recipes, HolderLookup.Provider registries) {
        Set<Item> items = new HashSet<>();
        for (Recipe<?> r : recipes) {
            ItemStack out = output(r, registries);
            if (!out.isEmpty()) items.add(out.getItem());
        }
        return items;
    }

    /** Everything craftable from the natural items with these recipes (a worklist over ingredients). */
    private static Set<Item> craftable(Collection<? extends Recipe<?>> recipes, Set<Item> natural, HolderLookup.Provider registries) {
        List<Node> nodes = new ArrayList<>();
        for (Recipe<?> r : recipes) {
            ItemStack out = output(r, registries);
            List<Ingredient> ingredients = nonEmpty(r);
            // Special recipes (map cloning, fireworks...) have no fixed ingredients or output.
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

    /**
     * Items that can be crafted with {@code before} but not with {@code after}, sorted by id.
     *
     * @param ignore items the operator is removing on purpose (e.g. "make unobtainable")
     */
    public static List<Item> lostItems(Collection<? extends Recipe<?>> before, Collection<? extends Recipe<?>> after,
                                       Set<Item> ignore, HolderLookup.Provider registries) {
        Set<Item> natural = new HashSet<>();
        Set<Item> made = produced(before, registries);
        for (Item item : BuiltInRegistries.ITEM) if (!made.contains(item)) natural.add(item);
        Set<Item> was = craftable(before, natural, registries);
        Set<Item> now = craftable(after, natural, registries);
        List<Item> lost = new ArrayList<>();
        for (Item item : was) if (!now.contains(item) && !ignore.contains(item)) lost.add(item);
        lost.sort(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()));
        return lost;
    }

    /** Recipes that remain after removing some ids (and optionally adding/replacing one). */
    public static List<Recipe<?>> without(Collection<RecipeHolder<?>> all, Set<ResourceLocation> removed) {
        List<Recipe<?>> list = new ArrayList<>();
        for (RecipeHolder<?> h : all) if (!removed.contains(h.id())) list.add(h.value());
        return list;
    }

    /** Warnings for duplication loops the new recipe would create with existing recipes. */
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

    /** Turns lost items into warning lines (at most {@code max}, plus a "+N more" line). */
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
