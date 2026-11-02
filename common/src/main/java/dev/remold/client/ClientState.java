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

    public static void setChanges(List<ChangeEntry> list, boolean pending) {
        changes = List.copyOf(list);
        pendingApply = pending;
    }

    public static void reset() {
        annotations = Map.of();
        changes = List.of();
        pendingApply = false;
        dev.remold.core.TemplateCache.clear();
    }
}
