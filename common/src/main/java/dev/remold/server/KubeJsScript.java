package dev.remold.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KubeJsScript {
    private static final Set<String> METHODS = Set.of("shaped", "shapeless", "smelting", "blasting", "smoking",
            "campfireCooking", "stonecutting", "smithing", "custom");
    private static final Set<String> CHAIN = Set.of("id", "xp", "cookingTime");
    private static final Pattern COUNTED = Pattern.compile("^(\\d+)x\\s+(.+)$");
    private static final Object UNSUPPORTED = new Object();

    private KubeJsScript() {}

    public record Entry(int start, int end, String receiver, String method, @Nullable String id,
                        @Nullable JsonObject json, String output, String problem) {
        public boolean editable() {
            return json != null && problem.isEmpty();
        }
    }

    private record ItemOf(String id, int count) {}

    private record IngredientOf(Object value) {}

    private static final class Fail extends RuntimeException {
        Fail() {
            super(null, null, false, false);
        }
    }

    public static List<Entry> parse(String src) {
        List<Entry> entries = new ArrayList<>();
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i = lineEnd(src, i);
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int j = src.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(src, i);
                continue;
            }
            if (Character.isJavaIdentifierStart(c) && (i == 0 || (!Character.isJavaIdentifierPart(src.charAt(i - 1)) && src.charAt(i - 1) != '.'))) {
                int identEnd = identEnd(src, i);
                String receiver = src.substring(i, identEnd);
                int p = skipSpace(src, identEnd);
                if (p < n && src.charAt(p) == '.') {
                    int m = skipSpace(src, p + 1);
                    int methodEnd = identEnd(src, m);
                    String method = src.substring(m, methodEnd);
                    int open = skipSpace(src, methodEnd);
                    if (METHODS.contains(method) && open < n && src.charAt(open) == '(') {
                        Entry entry = read(src, i, receiver, method, open);
                        if (entry != null) {
                            entries.add(entry);
                            i = entry.end();
                            continue;
                        }
                    }
                }
                i = identEnd;
                continue;
            }
            i++;
        }
        return entries;
    }

    @Nullable
    private static Entry read(String src, int start, String receiver, String method, int open) {
        int close = matching(src, open);
        if (close < 0) return null;
        int end = close + 1;
        String id = null;
        String problem = "";
        Map<String, Object> chain = new LinkedHashMap<>();
        while (true) {
            int p = skipSpace(src, end);
            if (p >= src.length() || src.charAt(p) != '.') break;
            int m = skipSpace(src, p + 1);
            int nameEnd = identEnd(src, m);
            String name = src.substring(m, nameEnd);
            int o = skipSpace(src, nameEnd);
            if (name.isEmpty() || o >= src.length() || src.charAt(o) != '(') break;
            int c = matching(src, o);
            if (c < 0) break;
            List<Object> args = args(src, o, c);
            if (!CHAIN.contains(name) || args == null || args.size() != 1) problem = "unsupported";
            else chain.put(name, args.getFirst());
            end = c + 1;
        }
        if (chain.get("id") instanceof String s) id = s;
        List<Object> args = args(src, open, close);
        JsonObject json = null;
        if (args == null) {
            problem = "unsupported";
        } else {
            try {
                json = toJson(method, args, chain);
            } catch (Fail e) {
                problem = "unsupported";
            }
        }
        return new Entry(start, end, receiver, method, id, json, json == null ? "" : outputOf(json), problem);
    }

    @Nullable
    private static List<Object> args(String src, int open, int close) {
        try {
            Reader r = new Reader(src.substring(open + 1, close));
            List<Object> list = new ArrayList<>();
            r.space();
            if (r.done()) return list;
            while (true) {
                Object v = r.value();
                if (v == UNSUPPORTED) return null;
                list.add(v);
                r.space();
                if (r.done()) return list;
                if (!r.eat(',')) return null;
                r.space();
                if (r.done()) return list;
            }
        } catch (Fail e) {
            return null;
        }
    }

    private static JsonObject toJson(String method, List<Object> a, Map<String, Object> chain) {
        JsonObject json = new JsonObject();
        switch (method) {
            case "shaped" -> {
                need(a, 3);
                json.addProperty("type", "minecraft:crafting_shaped");
                json.addProperty("category", "misc");
                if (!(a.get(1) instanceof List<?> rows)) throw new Fail();
                JsonArray pattern = new JsonArray();
                for (Object row : rows) {
                    if (!(row instanceof String s)) throw new Fail();
                    pattern.add(s);
                }
                if (!(a.get(2) instanceof Map<?, ?> keys)) throw new Fail();
                JsonObject key = new JsonObject();
                for (Map.Entry<?, ?> e : keys.entrySet()) key.add(e.getKey().toString(), ingredient(e.getValue()));
                json.add("pattern", pattern);
                json.add("key", key);
                json.add("result", result(a.getFirst()));
            }
            case "shapeless" -> {
                need(a, 2);
                json.addProperty("type", "minecraft:crafting_shapeless");
                json.addProperty("category", "misc");
                List<?> inputs = a.get(1) instanceof List<?> l ? l : List.of(a.get(1));
                JsonArray ingredients = new JsonArray();
                for (Object input : inputs) {
                    int times = 1;
                    Object value = input;
                    if (input instanceof String s) {
                        Matcher m = COUNTED.matcher(s);
                        if (m.matches()) {
                            times = Integer.parseInt(m.group(1));
                            value = m.group(2);
                        }
                    }
                    for (int t = 0; t < times; t++) ingredients.add(ingredient(value));
                }
                json.add("ingredients", ingredients);
                json.add("result", result(a.getFirst()));
            }
            case "smelting", "blasting", "smoking", "campfireCooking" -> {
                need(a, 2);
                String type = method.equals("campfireCooking") ? "campfire_cooking" : method;
                json.addProperty("type", "minecraft:" + type);
                json.addProperty("category", method.equals("smoking") || method.equals("campfireCooking") ? "food" : "misc");
                json.add("ingredient", ingredient(a.get(1)));
                json.add("result", result(a.getFirst()));
                Object xp = a.size() > 2 ? a.get(2) : chain.get("xp");
                Object time = a.size() > 3 ? a.get(3) : chain.get("cookingTime");
                json.addProperty("experience", xp instanceof Number x ? x.doubleValue() : 0.1);
                int defaultTime = switch (method) {
                    case "blasting", "smoking" -> 100;
                    case "campfireCooking" -> 600;
                    default -> 200;
                };
                json.addProperty("cookingtime", time instanceof Number t ? t.intValue() : defaultTime);
            }
            case "stonecutting" -> {
                need(a, 2);
                json.addProperty("type", "minecraft:stonecutting");
                json.add("ingredient", ingredient(a.get(1)));
                json.add("result", result(a.getFirst()));
            }
            case "smithing" -> {
                need(a, 4);
                json.addProperty("type", "minecraft:smithing_transform");
                json.add("template", ingredient(a.get(1)));
                json.add("base", ingredient(a.get(2)));
                json.add("addition", ingredient(a.get(3)));
                json.add("result", result(a.getFirst()));
            }
            case "custom" -> {
                need(a, 1);
                if (!(a.getFirst() instanceof Map<?, ?>)) throw new Fail();
                JsonElement e = plain(a.getFirst());
                return e.getAsJsonObject();
            }
            default -> throw new Fail();
        }
        return json;
    }

    private static void need(List<Object> args, int count) {
        if (args.size() != count) throw new Fail();
    }

    private static JsonElement ingredient(Object v) {
        if (v instanceof IngredientOf of) return ingredient(of.value());
        if (v instanceof ItemOf item) return item(item.id());
        if (v instanceof String s) {
            if (COUNTED.matcher(s).matches()) throw new Fail();
            if (s.startsWith("#")) {
                JsonObject o = new JsonObject();
                o.addProperty("tag", s.substring(1));
                return o;
            }
            return item(s);
        }
        if (v instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object o : list) array.add(ingredient(o));
            return array;
        }
        if (v instanceof Map<?, ?>) return plain(v);
        throw new Fail();
    }

    private static JsonObject item(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("item", id);
        return o;
    }

    private static JsonObject result(Object v) {
        JsonObject o = new JsonObject();
        if (v instanceof ItemOf item) {
            o.addProperty("id", item.id());
            if (item.count() != 1) o.addProperty("count", item.count());
            return o;
        }
        if (v instanceof String s) {
            Matcher m = COUNTED.matcher(s);
            if (m.matches()) {
                o.addProperty("id", m.group(2));
                o.addProperty("count", Integer.parseInt(m.group(1)));
            } else {
                o.addProperty("id", s);
            }
            return o;
        }
        if (v instanceof Map<?, ?>) return plain(v).getAsJsonObject();
        throw new Fail();
    }

    private static JsonElement plain(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof String s) return new JsonPrimitive(s);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return new JsonPrimitive(d.longValue());
            return new JsonPrimitive(d);
        }
        if (v instanceof List<?> list) {
            JsonArray a = new JsonArray();
            for (Object o : list) a.add(plain(o));
            return a;
        }
        if (v instanceof Map<?, ?> map) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : map.entrySet()) o.add(e.getKey().toString(), plain(e.getValue()));
            return o;
        }
        throw new Fail();
    }

    private static String outputOf(JsonObject json) {
        JsonElement r = null;
        for (String key : List.of("result", "output", "results", "outputs")) {
            if (json.has(key)) {
                r = json.get(key);
                break;
            }
        }
        if (r == null) return "";
        if (r.isJsonPrimitive()) return r.getAsString();
        if (r.isJsonArray() && !r.getAsJsonArray().isEmpty()) r = r.getAsJsonArray().get(0);
        if (r.isJsonObject()) {
            JsonObject o = r.getAsJsonObject();
            for (String k : List.of("id", "item")) if (o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsString();
        }
        return "";
    }

    public static String outputOfJson(JsonObject json) {
        return outputOf(json);
    }

    @Nullable
    public static String toJs(String receiver, JsonObject recipe, @Nullable String id) {
        String type = recipe.has("type") ? recipe.get("type").getAsString() : "";
        String call;
        try {
            call = switch (type) {
                case "minecraft:crafting_shaped" -> {
                    StringBuilder keys = new StringBuilder();
                    for (Map.Entry<String, JsonElement> e : recipe.getAsJsonObject("key").entrySet()) {
                        if (!keys.isEmpty()) keys.append(", ");
                        keys.append(keyName(e.getKey())).append(": ").append(jsIngredient(e.getValue()));
                    }
                    StringBuilder rows = new StringBuilder();
                    for (JsonElement row : recipe.getAsJsonArray("pattern")) {
                        if (!rows.isEmpty()) rows.append(", ");
                        rows.append(quote(row.getAsString()));
                    }
                    yield receiver + ".shaped(" + jsResult(recipe) + ", [" + rows + "], {" + keys + "})";
                }
                case "minecraft:crafting_shapeless" -> {
                    List<String> parts = new ArrayList<>();
                    for (JsonElement e : recipe.getAsJsonArray("ingredients")) parts.add(jsIngredient(e));
                    StringBuilder inputs = new StringBuilder();
                    for (int k = 0; k < parts.size(); ) {
                        int same = 1;
                        while (k + same < parts.size() && parts.get(k + same).equals(parts.get(k))) same++;
                        if (!inputs.isEmpty()) inputs.append(", ");
                        String part = parts.get(k);
                        inputs.append(same > 1 && part.startsWith("'") && !part.startsWith("'#") ? "'" + same + "x " + part.substring(1) : same > 1 ? repeat(part, same) : part);
                        k += same;
                    }
                    yield receiver + ".shapeless(" + jsResult(recipe) + ", [" + inputs + "])";
                }
                case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" -> {
                    String method = type.equals("minecraft:campfire_cooking") ? "campfireCooking" : type.substring("minecraft:".length());
                    StringBuilder sb = new StringBuilder(receiver + "." + method + "(" + jsResult(recipe) + ", " + jsIngredient(recipe.get("ingredient")) + ")");
                    if (recipe.has("experience")) sb.append(".xp(").append(number(recipe.get("experience"))).append(")");
                    if (recipe.has("cookingtime")) sb.append(".cookingTime(").append(number(recipe.get("cookingtime"))).append(")");
                    yield sb.toString();
                }
                case "minecraft:stonecutting" -> receiver + ".stonecutting(" + jsResult(recipe) + ", " + jsIngredient(recipe.get("ingredient")) + ")";
                case "minecraft:smithing_transform" -> receiver + ".smithing(" + jsResult(recipe) + ", " + jsIngredient(recipe.get("template"))
                        + ", " + jsIngredient(recipe.get("base")) + ", " + jsIngredient(recipe.get("addition")) + ")";
                default -> throw new Fail();
            };
        } catch (RuntimeException e) {
            call = receiver + ".custom(" + recipe + ")";
        }
        if (id != null) call += ".id(" + quote(id) + ")";
        return call;
    }

    private static String repeat(String part, int times) {
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < times; t++) {
            if (t > 0) sb.append(", ");
            sb.append(part);
        }
        return sb.toString();
    }

    private static String keyName(String key) {
        return key.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? key : quote(key);
    }

    private static String number(JsonElement e) {
        double d = e.getAsDouble();
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static String jsResult(JsonObject recipe) {
        JsonObject r = recipe.getAsJsonObject("result");
        if (r.has("components") || !r.has("id")) throw new Fail();
        int count = r.has("count") ? r.get("count").getAsInt() : 1;
        String id = r.get("id").getAsString();
        return count == 1 ? quote(id) : "Item.of(" + quote(id) + ", " + count + ")";
    }

    private static String jsIngredient(JsonElement e) {
        if (e.isJsonArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (JsonElement x : e.getAsJsonArray()) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(jsIngredient(x));
            }
            return sb.append(']').toString();
        }
        JsonObject o = e.getAsJsonObject();
        if (o.size() == 1 && o.has("item")) return quote(o.get("item").getAsString());
        if (o.size() == 1 && o.has("tag")) return quote("#" + o.get("tag").getAsString());
        throw new Fail();
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static int lineEnd(String s, int i) {
        int j = s.indexOf('\n', i);
        return j < 0 ? s.length() : j;
    }

    private static int skipString(String s, int i) {
        char q = s.charAt(i);
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == q) return j + 1;
            j++;
        }
        return s.length();
    }

    private static int identEnd(String s, int i) {
        int j = i;
        while (j < s.length() && Character.isJavaIdentifierPart(s.charAt(j))) j++;
        return j;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("//", i)) {
                i = lineEnd(s, i);
            } else if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2);
                i = j < 0 ? s.length() : j + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static int matching(String s, int open) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(s, i);
                continue;
            }
            if (s.startsWith("//", i)) {
                i = lineEnd(s, i);
                continue;
            }
            if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2);
                i = j < 0 ? s.length() : j + 2;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) return c == ')' ? i : -1;
            }
            i++;
        }
        return -1;
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        boolean done() {
            return i >= s.length();
        }

        void space() {
            i = skipSpace(s, i);
        }

        boolean eat(char c) {
            space();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        Object value() {
            space();
            if (done()) throw new Fail();
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') return string();
            if (c == '[') return array();
            if (c == '{') return object();
            if (c == '-' || c == '.' || Character.isDigit(c)) return number();
            if (Character.isJavaIdentifierStart(c)) return identifier();
            throw new Fail();
        }

        String string() {
            char q = s.charAt(i++);
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == q) return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> e;
                    });
                    continue;
                }
                if (q == '`' && c == '$' && i < s.length() && s.charAt(i) == '{') throw new Fail();
                sb.append(c);
            }
            throw new Fail();
        }

        List<Object> array() {
            i++;
            List<Object> list = new ArrayList<>();
            while (true) {
                if (eat(']')) return list;
                Object v = value();
                if (v == UNSUPPORTED) throw new Fail();
                list.add(v);
                if (eat(',')) continue;
                if (eat(']')) return list;
                throw new Fail();
            }
        }

        Map<String, Object> object() {
            i++;
            Map<String, Object> map = new LinkedHashMap<>();
            while (true) {
                if (eat('}')) return map;
                space();
                if (done()) throw new Fail();
                String key;
                char c = s.charAt(i);
                if (c == '\'' || c == '"' || c == '`') key = string();
                else if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) {
                    int end = identEnd(s, i);
                    key = s.substring(i, end);
                    i = end;
                } else throw new Fail();
                if (!eat(':')) throw new Fail();
                Object v = value();
                if (v == UNSUPPORTED) throw new Fail();
                map.put(key, v);
                if (eat(',')) continue;
                if (eat('}')) return map;
                throw new Fail();
            }
        }

        Double number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) i++;
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new Fail();
            }
        }

        Object identifier() {
            int end = identEnd(s, i);
            String name = s.substring(i, end);
            i = end;
            switch (name) {
                case "true": return Boolean.TRUE;
                case "false": return Boolean.FALSE;
                case "null": return null;
                default: break;
            }
            if ((name.equals("Item") || name.equals("Ingredient")) && eat('.')) {
                space();
                int mEnd = identEnd(s, i);
                String method = s.substring(i, mEnd);
                i = mEnd;
                if (!method.equals("of") || !eat('(')) throw new Fail();
                List<Object> args = new ArrayList<>();
                while (!eat(')')) {
                    Object v = value();
                    if (v == UNSUPPORTED) throw new Fail();
                    args.add(v);
                    eat(',');
                }
                if (name.equals("Ingredient")) {
                    if (args.size() != 1) throw new Fail();
                    return new IngredientOf(args.getFirst());
                }
                if (args.isEmpty() || !(args.getFirst() instanceof String id)) throw new Fail();
                if (args.size() == 1) {
                    Matcher m = COUNTED.matcher(id);
                    return m.matches() ? new ItemOf(m.group(2), Integer.parseInt(m.group(1))) : new ItemOf(id, 1);
                }
                if (args.size() == 2 && args.get(1) instanceof Double count) return new ItemOf(id, count.intValue());
                throw new Fail();
            }
            return UNSUPPORTED;
        }
    }
}
