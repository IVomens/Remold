package dev.remold.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.remold.Remold;
import dev.remold.data.Annotation;
import dev.remold.data.ChangeEntry;
import dev.remold.data.HistoryEntry;
import dev.remold.data.TagEdit;
import dev.remold.data.UnifySettings;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

public final class RemoldPack {
    public static final String FOLDER = "remold";
    public static final String PACK_ID = "file/" + FOLDER;
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Codec<List<ChangeEntry>> MANIFEST_CODEC = ChangeEntry.CODEC.listOf();
    private static final int MAX_UNDO_STEPS = 100;
    private static final int MAX_HISTORY_ENTRIES = 250_000;
    private static final Codec<List<HistoryEntry>> HISTORY_CODEC = HistoryEntry.CODEC.listOf();
    private static final Codec<Map<ResourceLocation, TagEdit>> TAGS_CODEC =
            Codec.unboundedMap(ResourceLocation.CODEC, TagEdit.CODEC);
    private static final Codec<Map<ResourceLocation, Annotation>> ANNOTATIONS_CODEC =
            Codec.unboundedMap(ResourceLocation.CODEC, Annotation.CODEC);

    private final MinecraftServer server;
    private final Path root;
    private final Map<ResourceLocation, ChangeEntry> changes = new LinkedHashMap<>();
    private final Map<ResourceLocation, Annotation> annotations = new TreeMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Map<ResourceLocation, TagEdit> tagEdits = new TreeMap<>();
    private UnifySettings unify = UnifySettings.EMPTY;
    private static final String UNIFY_FILE = "remold_unify.json";
    private static final String GATES_FILE = "remold_gates.json";
    private static final String STAGES_FILE = "remold_stages.json";
    private static final Codec<Map<ResourceLocation, String>> GATES_CODEC = Codec.unboundedMap(ResourceLocation.CODEC, Codec.STRING);
    private static final Codec<Map<String, List<String>>> STAGES_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf());
    private final Map<ResourceLocation, String> gates = new TreeMap<>();
    private final Map<java.util.UUID, java.util.Set<String>> stages = new java.util.HashMap<>();
    private long batch;
    private String batchAuthor = "";
    private boolean pendingApply;
    private CompletableFuture<Void> reloading;
    private boolean reloadQueued;

    private static RemoldPack instance;

    private RemoldPack(MinecraftServer server) {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(FOLDER);
        load();
    }

    public static RemoldPack get(MinecraftServer server) {
        if (instance == null || instance.server != server) {
            instance = new RemoldPack(server);
        }
        return instance;
    }

    public static void onServerStarting() {
        RemoldConfig.load();
    }

    public void flush() {
        try {
            if (dirtyManifest) {
                dirtyManifest = false;
                writeManifest();
            }
            if (dirtyHistory) {
                dirtyHistory = false;
                writeHistory();
            }
            if (dirtyTags) {
                dirtyTags = false;
                writeTags();
            }
            if (dirtyAnnotations) {
                dirtyAnnotations = false;
                writeAnnotations();
            }
        } catch (IOException e) {
            Remold.LOG.error("Could not save Remold's files", e);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (instance != null && instance.server == server) instance.flush();
        Unifier.tick(server);
        Gates.tick(server);
    }

    public static void onServerStopped() {
        if (instance != null) instance.flush();
        Gates.onServerStopped();
        instance = null;
    }

    public Path root() {
        return root;
    }

    private void load() {
        readJson(root.resolve("remold_manifest.json")).ifPresent(json ->
                MANIFEST_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad remold_manifest.json: {}", e))
                        .ifPresent(list -> list.forEach(c -> changes.put(c.id(), c))));

        readJson(root.resolve("remold_history.json")).ifPresent(json ->
                HISTORY_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad remold_history.json: {}", e))
                        .ifPresent(history::addAll));

        readJson(root.resolve("remold_tags.json")).ifPresent(json ->
                TAGS_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad remold_tags.json: {}", e))
                        .ifPresent(tagEdits::putAll));

        loadUnify();
        loadGates();
        readJson(root.resolve(STAGES_FILE)).flatMap(json -> STAGES_CODEC.parse(JsonOps.INSTANCE, json).result()).ifPresent(map ->
                map.forEach((uuid, list) -> {
                    try {
                        stages.put(java.util.UUID.fromString(uuid), new java.util.TreeSet<>(list));
                    } catch (IllegalArgumentException ignored) {
                    }
                }));

        Path global = dev.remold.platform.Services.PLATFORM.configDir().resolve(Remold.MOD_ID).resolve("annotations.json");
        for (Path file : List.of(global, root.resolve("remold_annotations.json"))) {
            readJson(file).ifPresent(json -> ANNOTATIONS_CODEC.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(e -> Remold.LOG.error("Bad {}: {}", file, e))
                    .ifPresent(annotations::putAll));
        }
    }

    private static Optional<JsonElement> readJson(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Remold.LOG.error("Could not read {}", file, e);
            return Optional.empty();
        }
    }

    private void writeJson(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private void ensurePack() throws IOException {
        Path mcmeta = root.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
            pack.addProperty("description", "Recipes made in-game with Remold");
            JsonObject meta = new JsonObject();
            meta.add("pack", pack);
            writeJson(mcmeta, meta);
        }
    }

    private boolean dirtyManifest;

    private void saveManifest() {
        dirtyManifest = true;
    }

    private void writeManifest() throws IOException {
        List<ChangeEntry> list = new ArrayList<>(changes.values());
        list.sort(Comparator.comparing(c -> c.id().toString()));
        writeJson(root.resolve("remold_manifest.json"),
                MANIFEST_CODEC.encodeStart(JsonOps.INSTANCE, list).getOrThrow());
    }

    private boolean dirtyAnnotations;

    private void saveAnnotations() {
        dirtyAnnotations = true;
    }

    private void writeAnnotations() throws IOException {
        ensurePack();
        writeJson(root.resolve("remold_annotations.json"),
                ANNOTATIONS_CODEC.encodeStart(JsonOps.INSTANCE, annotations).getOrThrow());
    }

    private boolean dirtyHistory;

    private void saveHistory() {
        dirtyHistory = true;
    }

    private void trimHistory() {
        if (history.isEmpty()) return;
        long newest = history.getLast().batch();
        while (!history.isEmpty()) {
            long oldest = history.getFirst().batch();
            if (oldest == newest) break;
            if (newest - oldest < MAX_UNDO_STEPS && history.size() <= MAX_HISTORY_ENTRIES) break;
            while (!history.isEmpty() && history.getFirst().batch() == oldest) history.removeFirst();
        }
    }

    private void writeHistory() throws IOException {
        trimHistory();
        writeJson(root.resolve("remold_history.json"), HISTORY_CODEC.encodeStart(JsonOps.INSTANCE, history).getOrThrow());
    }

    public void beginBatch(String author) {
        batch = (history.isEmpty() ? 0 : history.getLast().batch()) + 1;
        batchAuthor = author;
    }

    private void rememberRecipe(ResourceLocation id) throws IOException {
        Path file = recipeFile(id);
        Optional<String> previous = Files.isRegularFile(file)
                ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        history.add(new HistoryEntry(batch, false, id, previous, Optional.ofNullable(changes.get(id)),
                Optional.empty(), batchAuthor, System.currentTimeMillis(), false, Optional.empty(), Optional.empty()));
        ensurePack();
        saveHistory();
    }

    private void rememberAnnotation(ResourceLocation item) throws IOException {
        history.add(new HistoryEntry(batch, true, item, Optional.empty(), Optional.empty(),
                Optional.ofNullable(annotations.get(item)), batchAuthor, System.currentTimeMillis(), false, Optional.empty(), Optional.empty()));
        ensurePack();
        saveHistory();
    }

    private void rememberTag(ResourceLocation tag) throws IOException {
        Path file = tagFile(tag);
        Optional<String> previous = Files.isRegularFile(file)
                ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        history.add(new HistoryEntry(batch, false, tag, previous, Optional.empty(), Optional.empty(),
                batchAuthor, System.currentTimeMillis(), true, Optional.ofNullable(tagEdits.get(tag)), Optional.empty()));
        ensurePack();
        saveHistory();
    }

    private void loadUnify() {
        unify = readJson(root.resolve(UNIFY_FILE))
                .flatMap(json -> UnifySettings.CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(e -> Remold.LOG.error("Bad {}: {}", UNIFY_FILE, e)))
                .orElse(UnifySettings.EMPTY);
        Unifier.invalidate();
    }

    public UnifySettings unify() {
        return unify;
    }

    public void setUnify(UnifySettings settings) throws IOException {
        rememberConfig(UNIFY_FILE);
        Path file = root.resolve(UNIFY_FILE);
        unify = settings;
        Unifier.invalidate();
        writeJson(file, UnifySettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow());
    }

    private void loadGates() {
        gates.clear();
        readJson(root.resolve(GATES_FILE)).flatMap(json -> GATES_CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(e -> Remold.LOG.error("Bad {}: {}", GATES_FILE, e))).ifPresent(gates::putAll);
    }

    public Map<ResourceLocation, String> gates() {
        return Map.copyOf(gates);
    }

    public void setGates(Map<ResourceLocation, String> changes) throws IOException {
        rememberConfig(GATES_FILE);
        changes.forEach((id, requirement) -> {
            if (requirement.isEmpty()) gates.remove(id);
            else gates.put(id, requirement);
        });
        writeJson(root.resolve(GATES_FILE), GATES_CODEC.encodeStart(JsonOps.INSTANCE, gates).getOrThrow());
    }

    public java.util.Set<String> stages(java.util.UUID player) {
        return java.util.Set.copyOf(stages.getOrDefault(player, java.util.Set.of()));
    }

    public boolean setStage(java.util.UUID player, String stage, boolean granted) throws IOException {
        java.util.Set<String> set = stages.computeIfAbsent(player, p -> new java.util.TreeSet<>());
        boolean changed = granted ? set.add(stage) : set.remove(stage);
        if (changed) {
            ensurePack();
            Map<String, List<String>> out = new TreeMap<>();
            stages.forEach((uuid, s) -> {
                if (!s.isEmpty()) out.put(uuid.toString(), List.copyOf(s));
            });
            writeJson(root.resolve(STAGES_FILE), STAGES_CODEC.encodeStart(JsonOps.INSTANCE, out).getOrThrow());
        }
        return changed;
    }

    private static final String EXTERNAL = "external:";

    public void writeExternal(Path file, String content) throws IOException {
        ensurePack();
        Optional<String> previous = Files.isRegularFile(file)
                ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        String relative = server.getServerDirectory().toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        history.add(new HistoryEntry(batch, false, Remold.id("external"), previous, Optional.empty(), Optional.empty(),
                batchAuthor, System.currentTimeMillis(), false, Optional.empty(), Optional.of(EXTERNAL + relative)));
        saveHistory();
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        pendingApply = true;
    }

    private void rememberConfig(String fileName) throws IOException {
        ensurePack();
        Path file = root.resolve(fileName);
        Optional<String> previous = Files.isRegularFile(file)
                ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        history.add(new HistoryEntry(batch, false, Remold.id(fileName.replace(".json", "")), previous, Optional.empty(), Optional.empty(),
                batchAuthor, System.currentTimeMillis(), false, Optional.empty(), Optional.of(fileName)));
        saveHistory();
    }

    public record UndoResult(int changes, boolean recipes, boolean annotations, String author) {}

    public Optional<UndoResult> undo() throws IOException {
        if (history.isEmpty()) return Optional.empty();
        long last = history.getLast().batch();
        int count = 0;
        boolean recipes = false, notes = false;
        String author = history.getLast().author();
        while (!history.isEmpty() && history.getLast().batch() == last) {
            HistoryEntry entry = history.removeLast();
            count++;
            if (entry.configFile().isPresent() && entry.configFile().get().startsWith(EXTERNAL)) {
                Path file = server.getServerDirectory().resolve(entry.configFile().get().substring(EXTERNAL.length()));
                if (entry.previousFile().isPresent()) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.previousFile().get(), StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(file);
                }
                recipes = true;
                continue;
            }
            if (entry.configFile().isPresent()) {
                Path file = root.resolve(entry.configFile().get());
                if (entry.previousFile().isPresent()) Files.writeString(file, entry.previousFile().get(), StandardCharsets.UTF_8);
                else Files.deleteIfExists(file);
                loadUnify();
                loadGates();
                notes = true;
                continue;
            }
            if (entry.annotation()) {
                notes = true;
                entry.previousAnnotation().ifPresentOrElse(a -> annotations.put(entry.id(), a), () -> annotations.remove(entry.id()));
                continue;
            }
            if (entry.tag()) {
                recipes = true;
                Path file = tagFile(entry.id());
                if (entry.previousFile().isPresent()) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.previousFile().get(), StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(file);
                }
                entry.previousTagEdit().ifPresentOrElse(e -> tagEdits.put(entry.id(), e), () -> tagEdits.remove(entry.id()));
                saveTags();
                continue;
            }
            recipes = true;
            Path file = recipeFile(entry.id());
            if (entry.previousFile().isPresent()) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.previousFile().get(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(file);
            }
            entry.previousChange().ifPresentOrElse(c -> changes.put(entry.id(), c), () -> changes.remove(entry.id()));
        }
        saveHistory();
        if (recipes) {
            saveManifest();
            pendingApply = true;
        }
        if (notes) saveAnnotations();
        return Optional.of(new UndoResult(count, recipes, notes, author));
    }

    public boolean canUndo() {
        return !history.isEmpty();
    }

    public Path tagFile(ResourceLocation tag) {
        return root.resolve("data").resolve(tag.getNamespace()).resolve("tags").resolve("item").resolve(tag.getPath() + ".json");
    }

    private boolean dirtyTags;

    private void saveTags() {
        dirtyTags = true;
    }

    private void writeTags() throws IOException {
        writeJson(root.resolve("remold_tags.json"), TAGS_CODEC.encodeStart(JsonOps.INSTANCE, tagEdits).getOrThrow());
    }

    public Map<ResourceLocation, TagEdit> tagEdits() {
        return Map.copyOf(tagEdits);
    }

    public void setTagEdit(ResourceLocation tag, TagEdit edit, List<String> current) throws IOException {
        ensurePack();
        rememberTag(tag);
        if (edit.isEmpty()) {
            tagEdits.remove(tag);
            Files.deleteIfExists(tagFile(tag));
        } else {
            TagEdit previous = tagEdits.getOrDefault(tag, TagEdit.EMPTY);
            List<String> base = new ArrayList<>(current);
            for (String r : previous.remove()) if (!edit.remove().contains(r) && !r.startsWith("#") && !base.contains(r)) base.add(r);
            for (String a : previous.add()) if (!edit.add().contains(a)) base.remove(a);
            tagEdits.put(tag, edit);
            writeJson(tagFile(tag), dev.remold.platform.Services.PLATFORM.tagJson(edit.add(), edit.remove(), base));
        }
        saveTags();
        pendingApply = true;
    }

    public Path recipeFile(ResourceLocation id) {
        return root.resolve("data").resolve(id.getNamespace()).resolve("recipe").resolve(id.getPath() + ".json");
    }

    public void writeRecipe(ResourceLocation id, JsonElement json, ChangeEntry entry) throws IOException {
        ensurePack();
        rememberRecipe(id);
        writeJson(recipeFile(id), json);
        changes.put(id, entry);
        saveManifest();
        pendingApply = true;
    }

    public boolean revert(ResourceLocation id) throws IOException {
        if (Files.exists(recipeFile(id)) || changes.containsKey(id)) rememberRecipe(id);
        boolean existed = Files.deleteIfExists(recipeFile(id));
        existed |= changes.remove(id) != null;
        if (existed) {
            saveManifest();
            pendingApply = true;
        }
        return existed;
    }

    public Optional<ChangeEntry> change(ResourceLocation id) {
        return Optional.ofNullable(changes.get(id));
    }

    public List<ChangeEntry> changes() {
        return List.copyOf(changes.values());
    }

    public boolean pendingApply() {
        return pendingApply;
    }

    public Map<ResourceLocation, Annotation> annotations() {
        return Map.copyOf(annotations);
    }

    public void setAnnotation(ResourceLocation item, Annotation annotation) throws IOException {
        rememberAnnotation(item);
        if (annotation.isEmpty()) {
            annotations.remove(item);
        } else {
            annotations.put(item, annotation);
        }
        saveAnnotations();
    }

    public CompletableFuture<Void> apply() {
        flush();
        if (reloading != null && !reloading.isDone()) {
            reloadQueued = true;
            return reloading;
        }
        PackRepository repo = server.getPackRepository();
        repo.reload();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.remove(PACK_ID);
        if (repo.isAvailable(PACK_ID)) {
            selected.add(PACK_ID);
        }
        pendingApply = false;
        reloading = server.reloadResources(selected).whenComplete((v, err) -> server.execute(() -> {
            if (err != null) {
                Remold.LOG.error("Reload failed", err);
            }
            if (reloadQueued) {
                reloadQueued = false;
                apply();
            }
        }));
        return reloading;
    }
}
