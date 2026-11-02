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

    public static boolean canUndo() {
        return canUndo;
    }

    public static void setChanges(List<ChangeEntry> list, boolean pending, boolean undo) {
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
        dev.remold.core.TemplateCache.clear();
    }
}
