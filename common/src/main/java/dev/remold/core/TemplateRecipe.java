package dev.remold.core;

import dev.remold.RemoldText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TemplateRecipe {
    public enum NodeStyle { INGREDIENT_ITEM, INGREDIENT_TAG, STACK_ID, STACK_ITEM, PLAIN_STRING }

    public record Node(List<Object> path, NodeStyle style, boolean output, @org.jetbrains.annotations.Nullable List<Object> countPath) {
        public Node(List<Object> path, NodeStyle style, boolean output) {
            this(path, style, output, null);
        }

        public boolean countable() {
            return countPath != null;
        }
    }

    private static final List<String> COUNT_KEYS = List.of("count", "amount");

    public record Param(List<Object> path, JsonPrimitive original) {
        public String label() {
            StringBuilder sb = new StringBuilder();
            for (Object o : path) {
                if (o instanceof Integer i) sb.append('[').append(i + 1).append(']');
                else {
                    if (!sb.isEmpty()) sb.append('.');
                    sb.append(o);
                }
            }
            return sb.toString();
        }
    }

    private static final Set<String> SKIPPED_KEYS = Set.of("type", "neoforge:conditions", "fabric:load_conditions", "conditions");

    public final ResourceLocation sourceId;
    public final JsonObject json;
    public final List<Node> inputs = new ArrayList<>();
    public final List<Node> outputs = new ArrayList<>();
    public final List<Param> params = new ArrayList<>();

    private TemplateRecipe(ResourceLocation sourceId, JsonObject json) {
        this.sourceId = sourceId;
        this.json = json;
        walk(json, new ArrayList<>(), false);
        findCounts();
    }

    public static DataResult<TemplateRecipe> of(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        return Recipe.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), holder.value())
                .flatMap(json -> json.isJsonObject()
                        ? DataResult.success(new TemplateRecipe(holder.id(), json.getAsJsonObject()))
                        : DataResult.error(() -> RemoldText.error("not_object")));
    }

    public static TemplateRecipe of(ResourceLocation id, JsonObject json) {
        return new TemplateRecipe(id, json);
    }

    private static boolean isItem(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return false;
        ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
        return id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id) != Items.AIR;
    }

    private static boolean outputKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("result") || k.contains("output") || k.contains("product");
    }

    private void walk(JsonElement element, List<Object> path, boolean inOutput) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                walkChild(array.get(i), append(path, i), inOutput, null);
            }
        } else if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (SKIPPED_KEYS.contains(entry.getKey())) continue;
                walkChild(entry.getValue(), append(path, entry.getKey()), inOutput || outputKey(entry.getKey()), entry.getKey());
            }
        }
    }

    private void walkChild(JsonElement child, List<Object> path, boolean inOutput, String key) {
        if (child.isJsonObject()) {
            JsonObject obj = child.getAsJsonObject();
            if (obj.has("item") && isItem(obj.get("item"))) {
                add(new Node(path, inOutput ? NodeStyle.STACK_ITEM : NodeStyle.INGREDIENT_ITEM, inOutput));
                paramsOf(obj, path, inOutput ? Set.of("item", "count") : Set.of("item"));
                return;
            }
            if (obj.has("tag") && obj.get("tag").isJsonPrimitive() && !inOutput) {
                add(new Node(path, NodeStyle.INGREDIENT_TAG, false));
                paramsOf(obj, path, Set.of("tag"));
                return;
            }
            if (obj.has("id") && isItem(obj.get("id"))) {
                boolean output = inOutput || !inputHint(path);
                add(new Node(path, NodeStyle.STACK_ID, output));
                paramsOf(obj, path, output ? Set.of("id", "components", "count") : Set.of("id", "components"));
                return;
            }
            walk(obj, path, inOutput);
        } else if (child.isJsonArray()) {
            walk(child, path, inOutput);
        } else if (child.isJsonPrimitive()) {
            JsonPrimitive p = child.getAsJsonPrimitive();
            if (isItem(p) && (inOutput || inputHint(path))) {
                add(new Node(path, NodeStyle.PLAIN_STRING, inOutput));
            } else if (key == null || !SKIPPED_KEYS.contains(key)) {
                params.add(new Param(path, p));
            }
        }
    }

    private void paramsOf(JsonObject obj, List<Object> path, Set<String> skip) {
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (!skip.contains(e.getKey()) && !SKIPPED_KEYS.contains(e.getKey()) && e.getValue().isJsonPrimitive()) {
                params.add(new Param(append(path, e.getKey()), e.getValue().getAsJsonPrimitive()));
            }
        }
    }

    private static boolean inputHint(List<Object> path) {
        for (Object o : path) {
            if (o instanceof String s) {
                String k = s.toLowerCase(Locale.ROOT);
                if (k.contains("ingredient") || k.contains("input") || k.contains("key") || k.contains("base")
                        || k.contains("addition") || k.contains("template") || k.contains("catalyst")) return true;
            }
        }
        return false;
    }

    private void add(Node node) {
        (node.output() ? outputs : inputs).add(node);
    }

    private static List<Object> append(List<Object> path, Object next) {
        List<Object> copy = new ArrayList<>(path);
        copy.add(next);
        return copy;
    }

    private void findCounts() {
        List<List<Object>> countPaths = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Node node = inputs.get(i);
            if (node.style() == NodeStyle.PLAIN_STRING || node.style() == NodeStyle.STACK_ID) continue;
            List<Object> found = countPathFor(node.path());
            if (found != null) {
                inputs.set(i, new Node(node.path(), node.style(), false, found));
                countPaths.add(found);
            }
        }
        params.removeIf(p -> countPaths.contains(p.path()));
    }

    @org.jetbrains.annotations.Nullable
    private List<Object> countPathFor(List<Object> path) {
        JsonElement own = get(json, path);
        if (own.isJsonObject()) {
            for (String key : COUNT_KEYS) {
                if (isNumber(own.getAsJsonObject().get(key))) return append(path, key);
            }
        }
        if (path.size() >= 2 && path.getLast() instanceof String) {
            List<Object> parentPath = path.subList(0, path.size() - 1);
            JsonElement parent = get(json, parentPath);
            if (parent.isJsonObject() && parent.getAsJsonObject().size() <= 3) {
                for (String key : COUNT_KEYS) {
                    if (isNumber(parent.getAsJsonObject().get(key))) return append(new ArrayList<>(parentPath), key);
                }
            }
        }
        return null;
    }

    private static boolean isNumber(@org.jetbrains.annotations.Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    public GhostIngredient initialInput(Node node) {
        JsonElement e = get(json, node.path());
        int count = node.countable() ? Math.max(1, get(json, node.countPath()).getAsInt()) : 1;
        if (node.style() == NodeStyle.INGREDIENT_TAG) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(e.getAsJsonObject().get("tag").getAsString()));
            ItemStack first = GhostIngredient.tagItems(tag).stream().findFirst().map(ItemStack::new).orElse(new ItemStack(Items.BARRIER));
            return new GhostIngredient(first.copyWithCount(count), tag);
        }
        return new GhostIngredient(stackOf(e, node.style()).copyWithCount(count), null);
    }

    public ItemStack initialOutput(Node node, HolderLookup.Provider registries) {
        JsonElement e = get(json, node.path());
        ItemStack stack = stackOf(e, node.style());
        if (node.style() == NodeStyle.STACK_ID && e.getAsJsonObject().has("components")) {
            DataComponentPatch.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), e.getAsJsonObject().get("components"))
                    .result().ifPresent(stack::applyComponents);
        }
        return stack;
    }

    private static ItemStack stackOf(JsonElement e, NodeStyle style) {
        String id = switch (style) {
            case PLAIN_STRING -> e.getAsString();
            case STACK_ID -> e.getAsJsonObject().get("id").getAsString();
            default -> e.getAsJsonObject().get("item").getAsString();
        };
        int count = 1;
        if (e.isJsonObject() && e.getAsJsonObject().has("count") && e.getAsJsonObject().get("count").isJsonPrimitive()) {
            count = Math.max(1, e.getAsJsonObject().get("count").getAsInt());
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count);
    }

    public DataResult<JsonObject> build(GhostIngredient[] inputs, ItemStack[] outputs, String[] paramValues, HolderLookup.Provider registries) {
        JsonObject copy = json.deepCopy();
        List<List<Object>> removals = new ArrayList<>();

        for (int i = 0; i < this.params.size(); i++) {
            Param param = this.params.get(i);
            String raw = paramValues[i];
            JsonPrimitive value;
            JsonPrimitive original = param.original();
            try {
                if (original.isBoolean()) value = new JsonPrimitive(Boolean.parseBoolean(raw.trim()));
                else if (original.isNumber()) {
                    String t = raw.trim();
                    value = t.contains(".") || t.contains("e") ? new JsonPrimitive(Double.parseDouble(t)) : new JsonPrimitive(Long.parseLong(t));
                } else value = new JsonPrimitive(raw);
            } catch (NumberFormatException e) {
                return DataResult.error(() -> RemoldText.error("not_number", param.label()));
            }
            set(copy, param.path(), value);
        }

        for (int i = 0; i < this.inputs.size(); i++) {
            Node node = this.inputs.get(i);
            GhostIngredient ghost = inputs[i];
            if (ghost.isEmpty()) {
                if (node.path().getLast() instanceof Integer) {
                    removals.add(node.path());
                    continue;
                }
                int slot = i + 1;
                return DataResult.error(() -> RemoldText.error("input_empty", slot));
            }
            String itemId = BuiltInRegistries.ITEM.getKey(ghost.stack().getItem()).toString();
            if (node.style() == NodeStyle.PLAIN_STRING) {
                set(copy, node.path(), new JsonPrimitive(itemId));
                continue;
            }
            JsonObject obj = get(copy, node.path()).getAsJsonObject();
            if (node.style() == NodeStyle.STACK_ID) {
                obj.addProperty("id", itemId);
                continue;
            }
            obj.remove("item");
            obj.remove("tag");
            if (ghost.tag() != null) obj.addProperty("tag", ghost.tag().location().toString());
            else obj.addProperty("item", itemId);
            if (node.countable()) set(copy, node.countPath(), new JsonPrimitive(Math.max(1, ghost.stack().getCount())));
        }

        for (int i = 0; i < this.outputs.size(); i++) {
            Node node = this.outputs.get(i);
            ItemStack stack = outputs[i];
            if (stack.isEmpty()) {
                if (node.path().getLast() instanceof Integer) {
                    removals.add(node.path());
                    continue;
                }
                return DataResult.error(() -> RemoldText.error("output_empty"));
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (node.style() == NodeStyle.PLAIN_STRING) {
                set(copy, node.path(), new JsonPrimitive(itemId));
                continue;
            }
            JsonObject obj = get(copy, node.path()).getAsJsonObject();
            obj.addProperty(node.style() == NodeStyle.STACK_ID ? "id" : "item", itemId);
            if (stack.getCount() != 1 || obj.has("count")) obj.addProperty("count", stack.getCount());
            if (node.style() == NodeStyle.STACK_ID) {
                DataComponentPatch patch = stack.getComponentsPatch();
                if (patch.isEmpty()) {
                    obj.remove("components");
                } else {
                    DataResult<JsonElement> components = DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), patch);
                    if (components.error().isPresent()) return DataResult.error(() -> RemoldText.error("bad_item_data", components.error().get().message()));
                    obj.add("components", components.getOrThrow());
                }
            }
        }

        removals.sort(Comparator.comparing((List<Object> p) -> (Integer) p.getLast()).reversed());
        for (List<Object> path : removals) {
            JsonElement parent = get(copy, path.subList(0, path.size() - 1));
            if (parent.isJsonArray()) parent.getAsJsonArray().remove((int) (Integer) path.getLast());
        }
        return DataResult.success(copy);
    }

    @org.jetbrains.annotations.Nullable
    public JsonObject replaceItem(String from, String to, boolean inInputs, boolean inOutputs) {
        JsonObject copy = json.deepCopy();
        boolean changed = false;
        List<Node> nodes = new ArrayList<>();
        if (inInputs) nodes.addAll(inputs);
        if (inOutputs) nodes.addAll(outputs);
        for (Node node : nodes) {
            if (node.style() == NodeStyle.INGREDIENT_TAG) continue;
            JsonElement e = get(copy, node.path());
            if (node.style() == NodeStyle.PLAIN_STRING) {
                if (e.getAsString().equals(from)) {
                    set(copy, node.path(), new JsonPrimitive(to));
                    changed = true;
                }
                continue;
            }
            String key = node.style() == NodeStyle.STACK_ID ? "id" : "item";
            JsonObject obj = e.getAsJsonObject();
            if (obj.has(key) && obj.get(key).getAsString().equals(from)) {
                obj.addProperty(key, to);
                changed = true;
            }
        }
        return changed ? copy : null;
    }

    @org.jetbrains.annotations.Nullable
    public JsonObject widenToTag(Set<String> items, String tag) {
        JsonObject copy = json.deepCopy();
        boolean changed = false;
        for (Node node : inputs) {
            if (node.style() != NodeStyle.INGREDIENT_ITEM) continue;
            JsonObject obj = get(copy, node.path()).getAsJsonObject();
            if (obj.has("item") && items.contains(obj.get("item").getAsString())) {
                obj.remove("item");
                obj.addProperty("tag", tag);
                changed = true;
            }
        }
        return changed ? copy : null;
    }

    static JsonElement get(JsonElement root, List<Object> path) {
        JsonElement e = root;
        for (Object o : path) {
            e = o instanceof Integer i ? e.getAsJsonArray().get(i) : e.getAsJsonObject().get((String) o);
        }
        return e;
    }

    static void set(JsonElement root, List<Object> path, JsonElement value) {
        JsonElement parent = get(root, path.subList(0, path.size() - 1));
        Object last = path.getLast();
        if (last instanceof Integer i) parent.getAsJsonArray().set(i, value);
        else parent.getAsJsonObject().add((String) last, value);
    }
}
