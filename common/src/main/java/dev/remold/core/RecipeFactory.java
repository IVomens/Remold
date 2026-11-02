package dev.remold.core;

import dev.remold.RemoldText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Turns editor state into real recipes (and back). Vanilla recipes are built as real recipe objects
 * and encoded with the game's own codec, so the JSON is always exactly what the game expects.
 */
public final class RecipeFactory {
    private RecipeFactory() {}

    // ------------------------------------------------------------------ machine switching

    public static void setMachine(EditorState state, Machine machine, HolderLookup.Provider registries) {
        Machine previous = state.machine;
        state.machine = machine;
        if (machine.isTemplate()) {
            loadExample(state, 0, registries);
            return;
        }
        state.template = null;
        state.templateError = null;
        state.params = new String[0];
        GhostIngredient[] old = state.inputs;
        state.inputs = EditorState.empty(machine.kind().inputs);
        // Keep what can be kept: grid -> grid keeps everything, otherwise keep the first input.
        if (previous.kind().grid && machine.kind().grid) {
            state.inputs = Arrays.copyOf(old, 9);
        } else if (old.length > 0) {
            GhostIngredient first = Arrays.stream(old).filter(g -> !g.isEmpty()).findFirst().orElse(GhostIngredient.EMPTY);
            state.inputs[machine.kind() == Machine.Kind.SMITHING ? 1 : 0] = first;
        }
        ItemStack output = state.output();
        state.outputs = new ItemStack[]{output};
        if (machine.kind().cooking() && !previous.kind().cooking()) state.cookTime = machine.kind().defaultCookTime();
    }

    public static void loadExample(EditorState state, int index, HolderLookup.Provider registries) {
        Machine machine = state.machine;
        if (machine.examples().isEmpty()) {
            state.template = null;
            state.templateError = RemoldText.error("no_examples");
            state.inputs = EditorState.empty(0);
            state.outputs = new ItemStack[0];
            return;
        }
        int i = Math.floorMod(index, machine.examples().size());
        state.exampleIndex = i;
        var pending = TemplateCache.get(machine.examples().get(i).id());
        if (pending.isEmpty()) {
            state.template = null;
            state.templateError = RemoldText.error("loading_template");
            state.inputs = EditorState.empty(0);
            state.outputs = new ItemStack[0];
            return;
        }
        DataResult<TemplateRecipe> result = pending.get();
        if (result.error().isPresent()) {
            state.template = null;
            state.templateError = RemoldText.error("bad_example", machine.examples().get(i).id(), result.error().get().message());
            state.inputs = EditorState.empty(0);
            state.outputs = new ItemStack[0];
            return;
        }
        TemplateRecipe template = result.getOrThrow();
        state.template = template;
        state.templateError = null;
        state.inputs = template.inputs.stream().map(template::initialInput).toArray(GhostIngredient[]::new);
        state.outputs = template.outputs.stream().map(n -> template.initialOutput(n, registries)).toArray(ItemStack[]::new);
        state.params = template.params.stream().map(p -> p.original().getAsString()).toArray(String[]::new);
    }

    // ------------------------------------------------------------------ building

    public static DataResult<JsonObject> build(EditorState state, HolderLookup.Provider registries) {
        Machine.Kind kind = state.machine.kind();
        if (kind == Machine.Kind.TEMPLATE) {
            if (state.template == null) return DataResult.error(() -> Objects.requireNonNullElse(state.templateError, RemoldText.error("no_template")));
            return state.template.build(state.inputs, state.outputs, state.params, registries).flatMap(json -> validate(json, registries));
        }
        ItemStack output = state.output();
        if (output.isEmpty()) return DataResult.error(() -> RemoldText.error("no_output"));
        String group = state.group;
        Recipe<?> recipe;
        try {
            recipe = switch (kind) {
                case SHAPED -> shaped(state, output);
                case SHAPELESS -> {
                    NonNullList<Ingredient> list = NonNullList.create();
                    for (GhostIngredient g : state.inputs) if (!g.isEmpty()) list.add(g.toIngredient());
                    if (list.isEmpty()) throw new IllegalArgumentException(RemoldText.error("no_inputs"));
                    yield new ShapelessRecipe(group, CraftingBookCategory.MISC, output.copy(), list);
                }
                case SMELTING -> new SmeltingRecipe(group, CookingBookCategory.MISC, single(state), output.copy(), state.experience, state.cookTime);
                case BLASTING -> new BlastingRecipe(group, CookingBookCategory.MISC, single(state), output.copy(), state.experience, state.cookTime);
                case SMOKING -> new SmokingRecipe(group, CookingBookCategory.FOOD, single(state), output.copy(), state.experience, state.cookTime);
                case CAMPFIRE -> new CampfireCookingRecipe(group, CookingBookCategory.FOOD, single(state), output.copy(), state.experience, state.cookTime);
                case STONECUTTING -> new StonecutterRecipe(group, single(state), output.copy());
                case SMITHING -> {
                    if (state.inputs[1].isEmpty()) throw new IllegalArgumentException(RemoldText.error("no_smithing_base"));
                    yield new SmithingTransformRecipe(state.inputs[0].toIngredient(), state.inputs[1].toIngredient(), state.inputs[2].toIngredient(), output.copy());
                }
                case TEMPLATE -> throw new IllegalStateException();
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return DataResult.error(e::getMessage);
        }
        return Recipe.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), recipe)
                .flatMap(json -> json.isJsonObject() ? DataResult.success(json.getAsJsonObject()) : DataResult.error(() -> RemoldText.error("not_object")))
                .flatMap(json -> validate(json, registries));
    }

    private static Ingredient single(EditorState state) {
        if (state.inputs[0].isEmpty()) throw new IllegalArgumentException(RemoldText.error("no_input"));
        return state.inputs[0].toIngredient();
    }

    private static ShapedRecipe shaped(EditorState state, ItemStack output) {
        GhostIngredient[] grid = state.inputs;
        int minX = 3, minY = 3, maxX = -1, maxY = -1;
        for (int i = 0; i < 9; i++) {
            if (grid[i].isEmpty()) continue;
            int x = i % 3, y = i / 3;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (maxX < 0) throw new IllegalArgumentException(RemoldText.error("no_inputs"));
        Map<String, Character> symbols = new LinkedHashMap<>();
        Map<Character, Ingredient> key = new HashMap<>();
        List<String> pattern = new ArrayList<>();
        String alphabet = "ABCDEFGHI";
        for (int y = minY; y <= maxY; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                GhostIngredient g = grid[y * 3 + x];
                if (g.isEmpty()) {
                    row.append(' ');
                    continue;
                }
                String identity = g.tag() != null ? "#" + g.tag().location() : BuiltInRegistries.ITEM.getKey(g.stack().getItem()).toString();
                Character symbol = symbols.computeIfAbsent(identity, k -> alphabet.charAt(symbols.size()));
                key.putIfAbsent(symbol, g.toIngredient());
                row.append(symbol);
            }
            pattern.add(row.toString());
        }
        return new ShapedRecipe(state.group, CraftingBookCategory.MISC, ShapedRecipePattern.of(key, pattern), output.copy());
    }

    /** Round-trips the JSON through the recipe codec, catching anything the target machine would reject. */
    private static DataResult<JsonObject> validate(JsonObject json, HolderLookup.Provider registries) {
        DataResult<Recipe<?>> parsed = Recipe.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json);
        return parsed.error().isPresent()
                ? DataResult.error(() -> parsed.error().get().message())
                : DataResult.success(json);
    }

    // ------------------------------------------------------------------ loading

    /** Fills the editor from an existing recipe, so it can be tweaked and saved over the original. */
    public static void load(EditorState state, RecipeHolder<?> holder, RecipeManager recipes, RegistryAccess registries) {
        Machine machine = Machines.forRecipe(holder, recipes, registries);
        state.machine = machine;
        state.loadedFrom = holder.id();
        state.recipeId = holder.id().toString();
        state.autoId = false;
        Recipe<?> recipe = holder.value();
        state.group = recipe.getGroup();
        if (machine.isTemplate()) {
            loadExample(state, Math.max(0, machine.examples().indexOf(holder)), registries);
            return;
        }
        state.template = null;
        state.params = new String[0];
        state.inputs = EditorState.empty(machine.kind().inputs);
        state.outputs = new ItemStack[]{recipe.getResultItem(registries).copy()};
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        switch (machine.kind()) {
            case SHAPED -> {
                ShapedRecipe shaped = (ShapedRecipe) recipe;
                for (int y = 0; y < shaped.getHeight(); y++)
                    for (int x = 0; x < shaped.getWidth(); x++)
                        state.inputs[y * 3 + x] = GhostIngredient.fromIngredient(ingredients.get(y * shaped.getWidth() + x), registries);
            }
            case SHAPELESS -> {
                for (int i = 0; i < Math.min(9, ingredients.size()); i++)
                    state.inputs[i] = GhostIngredient.fromIngredient(ingredients.get(i), registries);
            }
            case SMITHING -> {
                // SmithingTransformRecipe keeps its ingredients private; read them from its JSON form instead.
                Recipe.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), recipe).result().ifPresent(json -> {
                    String[] keys = {"template", "base", "addition"};
                    for (int i = 0; i < 3; i++) {
                        JsonElement e = json.getAsJsonObject().get(keys[i]);
                        if (e == null) continue;
                        int slot = i;
                        Ingredient.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), e).result()
                                .ifPresent(ing -> state.inputs[slot] = GhostIngredient.fromIngredient(ing, registries));
                    }
                });
            }
            default -> {
                if (!ingredients.isEmpty()) state.inputs[0] = GhostIngredient.fromIngredient(ingredients.getFirst(), registries);
                if (recipe instanceof AbstractCookingRecipe cooking) {
                    state.cookTime = cooking.getCookingTime();
                    state.experience = cooking.getExperience();
                }
            }
        }
    }

    /** Loads loose items (e.g. from a recipe viewer's "+" button for a recipe Remold can't read directly). */
    public static void loadItems(EditorState state, List<ItemStack> inputs, ItemStack output, boolean shaped, HolderLookup.Provider registries) {
        setMachine(state, shaped ? Machines.SHAPED : Machines.SHAPELESS, registries);
        state.inputs = EditorState.empty(9);
        for (int i = 0; i < Math.min(9, inputs.size()); i++) state.inputs[i] = GhostIngredient.of(inputs.get(i));
        state.outputs = new ItemStack[]{output.copy()};
        state.loadedFrom = null;
        state.autoId = true;
    }

    // ------------------------------------------------------------------ ids

    /** {@code remold:<output item>} with a numeric suffix if needed. */
    public static String suggestId(EditorState state, RecipeManager recipes) {
        ItemStack output = state.output();
        if (output.isEmpty()) return "";
        String base = BuiltInRegistries.ITEM.getKey(output.getItem()).getPath();
        String machine = state.machine.kind() == Machine.Kind.SHAPED || state.machine.kind() == Machine.Kind.SHAPELESS
                ? "" : "_from_" + state.machine.id().getPath().replace('/', '_');
        String candidate = base + machine;
        int n = 2;
        while (recipes.byKey(Remold.id(candidate)).isPresent()) {
            candidate = base + machine + "_" + n++;
        }
        return Remold.id(candidate).toString();
    }

    /** Parses what the operator typed. Ids without a namespace go in {@code remold:}. */
    public static DataResult<ResourceLocation> parseId(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return DataResult.error(() -> RemoldText.error("no_id"));
        if (!t.contains(":")) t = Remold.MOD_ID + ":" + t;
        String finalT = t;
        ResourceLocation id = ResourceLocation.tryParse(t);
        return id == null ? DataResult.error(() -> RemoldText.error("bad_id", finalT)) : DataResult.success(id);
    }
}
