package dev.remold.network;

import dev.remold.Remold;
import dev.remold.data.Annotation;
import dev.remold.data.ChangeEntry;
import dev.remold.data.TagEdit;
import dev.remold.data.UnifySettings;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Payloads {
    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(Remold.id(name));
    }

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
        REMOVE,

        REVERT,

        APPLY,

        SYNC,

        REMOVE_ALL_FOR_ITEM,

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
        REPLACE_ITEM,

        REMOVE_USING_ITEM,

        REMOVE_FROM_MOD
    }

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

    public record Unify(UnifySettings settings, boolean apply) implements CustomPacketPayload {
        public static final Type<Unify> TYPE = Payloads.typeOf("unify");
        public static final StreamCodec<ByteBuf, Unify> CODEC = StreamCodec.composite(
                UnifySettings.STREAM_CODEC, Unify::settings,
                ByteBufCodecs.BOOL, Unify::apply,
                Unify::new);

        @Override
        public Type<Unify> type() {
            return TYPE;
        }
    }

    public record SetGates(Map<ResourceLocation, String> gates) implements CustomPacketPayload {
        public static final Type<SetGates> TYPE = Payloads.typeOf("set_gates");
        public static final StreamCodec<ByteBuf, SetGates> CODEC = ByteBufCodecs
                .<ByteBuf, ResourceLocation, String, Map<ResourceLocation, String>>map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.STRING_UTF8)
                .map(SetGates::new, SetGates::gates);

        @Override
        public Type<SetGates> type() {
            return TYPE;
        }
    }

    public record RequestTemplate(ResourceLocation id) implements CustomPacketPayload {
        public static final Type<RequestTemplate> TYPE = Payloads.typeOf("request_template");
        public static final StreamCodec<ByteBuf, RequestTemplate> CODEC = ResourceLocation.STREAM_CODEC.map(RequestTemplate::new, RequestTemplate::id);

        @Override
        public Type<RequestTemplate> type() {
            return TYPE;
        }
    }

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
                              Map<ResourceLocation, TagEdit> tagEdits, UnifySettings unify) implements CustomPacketPayload {
        public static final Type<SyncChanges> TYPE = Payloads.typeOf("sync_changes");
        public static final StreamCodec<ByteBuf, SyncChanges> CODEC = StreamCodec.composite(
                ChangeEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncChanges::changes,
                ByteBufCodecs.BOOL, SyncChanges::pendingApply,
                ByteBufCodecs.BOOL, SyncChanges::canUndo,
                ByteBufCodecs.<ByteBuf, ResourceLocation, TagEdit, Map<ResourceLocation, TagEdit>>map(HashMap::new,
                        ResourceLocation.STREAM_CODEC, TagEdit.STREAM_CODEC), SyncChanges::tagEdits,
                UnifySettings.STREAM_CODEC, SyncChanges::unify,
                SyncChanges::new);

        @Override
        public Type<SyncChanges> type() {
            return TYPE;
        }
    }

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

    public record SyncGates(Map<ResourceLocation, String> gates, java.util.Set<String> unlocked) implements CustomPacketPayload {
        public static final Type<SyncGates> TYPE = Payloads.typeOf("sync_gates");
        public static final StreamCodec<ByteBuf, SyncGates> CODEC = StreamCodec.composite(
                ByteBufCodecs.<ByteBuf, ResourceLocation, String, Map<ResourceLocation, String>>map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.STRING_UTF8), SyncGates::gates,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.collection(java.util.HashSet::new)), SyncGates::unlocked,
                SyncGates::new);

        @Override
        public Type<SyncGates> type() {
            return TYPE;
        }
    }

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
