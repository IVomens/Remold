package dev.remold.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record HistoryEntry(long batch, boolean annotation, ResourceLocation id,
                           Optional<String> previousFile, Optional<ChangeEntry> previousChange,
                           Optional<Annotation> previousAnnotation, String author, long time,
                           boolean tag, Optional<TagEdit> previousTagEdit, Optional<String> configFile) {
    public static final Codec<HistoryEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("batch").forGetter(HistoryEntry::batch),
            Codec.BOOL.optionalFieldOf("annotation", false).forGetter(HistoryEntry::annotation),
            ResourceLocation.CODEC.fieldOf("id").forGetter(HistoryEntry::id),
            Codec.STRING.optionalFieldOf("previous_file").forGetter(HistoryEntry::previousFile),
            ChangeEntry.CODEC.optionalFieldOf("previous_change").forGetter(HistoryEntry::previousChange),
            Annotation.CODEC.optionalFieldOf("previous_annotation").forGetter(HistoryEntry::previousAnnotation),
            Codec.STRING.optionalFieldOf("author", "").forGetter(HistoryEntry::author),
            Codec.LONG.optionalFieldOf("time", 0L).forGetter(HistoryEntry::time),
            Codec.BOOL.optionalFieldOf("tag", false).forGetter(HistoryEntry::tag),
            TagEdit.CODEC.optionalFieldOf("previous_tag_edit").forGetter(HistoryEntry::previousTagEdit),
            Codec.STRING.optionalFieldOf("config_file").forGetter(HistoryEntry::configFile)
    ).apply(i, HistoryEntry::new));
}
