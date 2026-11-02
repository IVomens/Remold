package dev.remold.network;

import dev.remold.Remold;
import dev.remold.data.Annotation;
import dev.remold.data.ChangeEntry;
import dev.remold.data.TagEdit;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All of Remold's network payloads. They only use vanilla classes, so every loader can register
 * them with its own networking API (see {@code RemoldNeoForge}).
 */
public final class Payloads {
    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(Remold.id(name));
    }

    // ---------------------------------------------------------------- client -> server

    /** Save (create or replace) a recipe. {@code json} is a complete recipe JSON object. */
    public record SaveRecipe(ResourceLocation id, String json, boolean apply) implements CustomPacketPayload {
        public static final Type<SaveRecipe> TYPE = Payloads.typeOf("save_recipe");
        public static final StreamCodec<ByteBuf, SaveRecipe> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, SaveRecipe::id,
                ByteBufCodecs.stringUtf8(1 << 20), SaveRecipe::json,
                ByteBufCodecs.BOOL, SaveRecipe::apply,
                SaveRecipe::new);

        @Override
        public Type<SaveRecipe> type() {
            return TYPE;
        }
    }

    public enum Action {
        /** Disable an existing recipe. */
        REMOVE,
        /** Undo whatever Remold did to this recipe id. */
        REVERT,
        /** Reload datapacks so queued changes take effect. Id is ignored. */
        APPLY,
        /** Ask the server for the current change list. Id is ignored. */
        SYNC,
        /** Remove every recipe that produces the item with this id. */
        REMOVE_ALL_FOR_ITEM,
        /** Undo the most recent change. Id is ignored. */
        UNDO
    }

    public record RecipeAction(Action action, ResourceLocation id, boolean apply) implements CustomPacketPayload {
        public static final Type<RecipeAction> TYPE = Payloads.typeOf("recipe_action");
        public static final StreamCodec<ByteBuf, RecipeAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(o -> Action.values()[o], Action::ordinal), RecipeAction::action,
                ResourceLocation.STREAM_CODEC, RecipeAction::id,
                ByteBufCodecs.BOOL, RecipeAction::apply,
                RecipeAction::new);

        public static RecipeAction simple(Action action) {
            return new RecipeAction(action, Remold.id("none"), true);
        }

        @Override
        public Type<RecipeAction> type() {
            return TYPE;
        }
    }

    /** Set (or clear, when empty) the annotation of an item. */
    public record SetAnnotation(ResourceLocation item, Annotation annotation) implements CustomPacketPayload {
        public static final Type<SetAnnotation> TYPE = Payloads.typeOf("set_annotation");
        public static final StreamCodec<ByteBuf, SetAnnotation> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, SetAnnotation::item,
                Annotation.STREAM_CODEC, SetAnnotation::annotation,
                SetAnnotation::new);

        @Override
        public Type<SetAnnotation> type() {
            return TYPE;
        }
    }

    /** Replace Remold's edits of one item tag (the full set of additions and removals). */
    public record SetTag(ResourceLocation tag, TagEdit edit, boolean apply) implements CustomPacketPayload {
        public static final Type<SetTag> TYPE = Payloads.typeOf("set_tag");
        public static final StreamCodec<ByteBuf, SetTag> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, SetTag::tag,
                TagEdit.STREAM_CODEC, SetTag::edit,
                ByteBufCodecs.BOOL, SetTag::apply,
                SetTag::new);

        @Override
        public Type<SetTag> type() {
            return TYPE;
        }
    }

    public enum BulkKind {
        /** Replace item {@code a} with item {@code b}. Scope: 0 inputs and outputs, 1 inputs, 2 outputs. */
        REPLACE_ITEM,
        /** Remove every recipe that uses item {@code a} as an ingredient (tags included). */
        REMOVE_USING_ITEM,
        /** Remove every recipe from mod {@code a}. Scope: 0 by recipe id, 1 by the output item's mod. */
        REMOVE_FROM_MOD
    }

    /** One action applied to many recipes at once; undone as a single step. */
    public record Bulk(BulkKind kind, String a, String b, int scope, boolean apply) implements CustomPacketPayload {
        public static final Type<Bulk> TYPE = Payloads.typeOf("bulk");
        public static final StreamCodec<ByteBuf, Bulk> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(o -> BulkKind.values()[o], BulkKind::ordinal), Bulk::kind,
                ByteBufCodecs.STRING_UTF8, Bulk::a,
                ByteBufCodecs.STRING_UTF8, Bulk::b,
                ByteBufCodecs.VAR_INT, Bulk::scope,
                ByteBufCodecs.BOOL, Bulk::apply,
                Bulk::new);

        @Override
        public Type<Bulk> type() {
            return TYPE;
        }
    }

    /** List the packs in the import folder (file empty), or import one. */
    public record ImportRequest(String file, boolean apply) implements CustomPacketPayload {
        public static final Type<ImportRequest> TYPE = Payloads.typeOf("import_request");
        public static final StreamCodec<ByteBuf, ImportRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ImportRequest::file,
                ByteBufCodecs.BOOL, ImportRequest::apply,
                ImportRequest::new);

        @Override
        public Type<ImportRequest> type() {
            return TYPE;
        }
    }

    /** Ask the server for a recipe's original JSON, to use as a template for a machine. */
    public record RequestTemplate(ResourceLocation id) implements CustomPacketPayload {
        public static final Type<RequestTemplate> TYPE = Payloads.typeOf("request_template");
        public static final StreamCodec<ByteBuf, RequestTemplate> CODEC = ResourceLocation.STREAM_CODEC.map(RequestTemplate::new, RequestTemplate::id);

        @Override
        public Type<RequestTemplate> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- server -> client

    /**
     * A recipe as JSON, encoded on the server. Recipes on the client are network copies that have
     * lost details (tags are expanded, shaped patterns lose their keys), so templates come from here.
     */
    public record TemplateJson(ResourceLocation id, String json, String error) implements CustomPacketPayload {
        public static final Type<TemplateJson> TYPE = Payloads.typeOf("template_json");
        public static final StreamCodec<ByteBuf, TemplateJson> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, TemplateJson::id,
                ByteBufCodecs.stringUtf8(1 << 20), TemplateJson::json,
                ByteBufCodecs.STRING_UTF8, TemplateJson::error,
                TemplateJson::new);

        @Override
        public Type<TemplateJson> type() {
            return TYPE;
        }
    }

    public record OpenEditor() implements CustomPacketPayload {
        public static final OpenEditor INSTANCE = new OpenEditor();
        public static final Type<OpenEditor> TYPE = Payloads.typeOf("open_editor");
        public static final StreamCodec<ByteBuf, OpenEditor> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<OpenEditor> type() {
            return TYPE;
        }
    }

    public record SyncAnnotations(Map<ResourceLocation, Annotation> annotations) implements CustomPacketPayload {
        public static final Type<SyncAnnotations> TYPE = Payloads.typeOf("sync_annotations");
        public static final StreamCodec<ByteBuf, SyncAnnotations> CODEC = ByteBufCodecs
                .<ByteBuf, ResourceLocation, Annotation, Map<ResourceLocation, Annotation>>map(HashMap::new,
                        ResourceLocation.STREAM_CODEC, Annotation.STREAM_CODEC)
                .map(SyncAnnotations::new, SyncAnnotations::annotations);

        @Override
        public Type<SyncAnnotations> type() {
            return TYPE;
        }
    }

    public record SyncChanges(List<ChangeEntry> changes, boolean pendingApply, boolean canUndo,
                              Map<ResourceLocation, TagEdit> tagEdits) implements CustomPacketPayload {
        public static final Type<SyncChanges> TYPE = Payloads.typeOf("sync_changes");
        public static final StreamCodec<ByteBuf, SyncChanges> CODEC = StreamCodec.composite(
                ChangeEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncChanges::changes,
                ByteBufCodecs.BOOL, SyncChanges::pendingApply,
                ByteBufCodecs.BOOL, SyncChanges::canUndo,
                ByteBufCodecs.<ByteBuf, ResourceLocation, TagEdit, Map<ResourceLocation, TagEdit>>map(HashMap::new,
                        ResourceLocation.STREAM_CODEC, TagEdit.STREAM_CODEC), SyncChanges::tagEdits,
                SyncChanges::new);

        @Override
        public Type<SyncChanges> type() {
            return TYPE;
        }
    }

    /** Who may edit on this server, so the client knows whether to show the inventory button. */
    public record Access(int permissionLevel, boolean listed) implements CustomPacketPayload {
        public static final Type<Access> TYPE = Payloads.typeOf("access");
        public static final StreamCodec<ByteBuf, Access> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Access::permissionLevel,
                ByteBufCodecs.BOOL, Access::listed,
                Access::new);

        @Override
        public Type<Access> type() {
            return TYPE;
        }
    }

    /** One pack in the import folder. {@code error} is empty when it could be read. */
    public record ImportSummary(String file, int recipes, int removals, int tags, int annotations, String error) {
        public static final StreamCodec<ByteBuf, ImportSummary> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ImportSummary::file,
                ByteBufCodecs.VAR_INT, ImportSummary::recipes,
                ByteBufCodecs.VAR_INT, ImportSummary::removals,
                ByteBufCodecs.VAR_INT, ImportSummary::tags,
                ByteBufCodecs.VAR_INT, ImportSummary::annotations,
                ByteBufCodecs.STRING_UTF8, ImportSummary::error,
                ImportSummary::new);
    }

    public record ImportList(List<ImportSummary> files, String folder) implements CustomPacketPayload {
        public static final Type<ImportList> TYPE = Payloads.typeOf("import_list");
        public static final StreamCodec<ByteBuf, ImportList> CODEC = StreamCodec.composite(
                ImportSummary.CODEC.apply(ByteBufCodecs.list()), ImportList::files,
                ByteBufCodecs.STRING_UTF8, ImportList::folder,
                ImportList::new);

        @Override
        public Type<ImportList> type() {
            return TYPE;
        }
    }

    /** Feedback for the operator, shown as a toast. Title and message are translation keys. */
    public record Notice(boolean success, String title, String message, List<String> args) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = Payloads.typeOf("notice");
        public static final StreamCodec<ByteBuf, Notice> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Notice::success,
                ByteBufCodecs.STRING_UTF8, Notice::title,
                ByteBufCodecs.STRING_UTF8, Notice::message,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Notice::args,
                Notice::new);

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }
}
