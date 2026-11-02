package dev.remold.client;

import dev.remold.compat.ViewerBridge;
import dev.remold.core.EditorState;
import dev.remold.data.Annotation;
import dev.remold.data.ChangeEntry;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/** Data the server has sent us, plus the operator's current draft. */
public final class ClientState {
    private static Map<ResourceLocation, Annotation> annotations = Map.of();
    private static List<ChangeEntry> changes = List.of();
    private static boolean pendingApply;
    private static int permissionLevel = dev.remold.Remold.PERMISSION_LEVEL;
    private static boolean listed;
    /** The draft survives closing and re-opening the editor. */
    public static final EditorState EDITOR = new EditorState();

    private ClientState() {}

    public static Map<ResourceLocation, Annotation> annotations() {
        return annotations;
    }

    public static void setAnnotations(Map<ResourceLocation, Annotation> map) {
        annotations = Map.copyOf(map);
        ViewerBridge.fireAnnotationsChanged();
    }

    public static List<ChangeEntry> changes() {
        return changes;
    }

    public static boolean pendingApply() {
        return pendingApply;
    }

    private static boolean canUndo;
    private static Map<ResourceLocation, String> gates = Map.of();
    private static java.util.Set<String> unlocked = java.util.Set.of();

    public static void setGates(Map<ResourceLocation, String> map, java.util.Set<String> met) {
        gates = Map.copyOf(map);
        unlocked = java.util.Set.copyOf(met);
    }

    public static Map<ResourceLocation, String> gates() {
        return gates;
    }

    /** The requirement locking this recipe, or null if it isn't locked. */
    @org.jetbrains.annotations.Nullable
    public static String gate(ResourceLocation recipe) {
        return gates.get(recipe);
    }

    public static boolean isUnlocked(String requirement) {
        return unlocked.contains(requirement);
    }
    private static Map<ResourceLocation, dev.remold.data.TagEdit> tagEdits = Map.of();

    public static Map<ResourceLocation, dev.remold.data.TagEdit> tagEdits() {
        return tagEdits;
    }

    public static boolean canUndo() {
        return canUndo;
    }

    private static dev.remold.data.UnifySettings unify = dev.remold.data.UnifySettings.EMPTY;

    public static dev.remold.data.UnifySettings unify() {
        return unify;
    }

    public static void setChanges(List<ChangeEntry> list, boolean pending, boolean undo, Map<ResourceLocation, dev.remold.data.TagEdit> tags,
                                  dev.remold.data.UnifySettings unifySettings) {
        unify = unifySettings;
        tagEdits = Map.copyOf(tags);
        changes = List.copyOf(list);
        pendingApply = pending;
        canUndo = undo;
    }

    public static void setAccess(int level, boolean isListed) {
        permissionLevel = level;
        listed = isListed;
    }

    public static boolean canEdit(net.minecraft.world.entity.player.Player player) {
        return listed || player.hasPermissions(permissionLevel);
    }

    public static void reset() {
        permissionLevel = dev.remold.Remold.PERMISSION_LEVEL;
        listed = false;
        annotations = Map.of();
        changes = List.of();
        pendingApply = false;
        canUndo = false;
        gates = Map.of();
        unlocked = java.util.Set.of();
        tagEdits = Map.of();
        unify = dev.remold.data.UnifySettings.EMPTY;
        dev.remold.core.TemplateCache.clear();
    }
}
