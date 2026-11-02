package dev.remold.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * What something looked like before Remold changed it, so the change can be undone.
 * All entries with the same {@code batch} were one operator action and are undone together.
 *
 * @param previousFile       the recipe file's previous content (absent: there was no file)
 * @param previousChange     the previous manifest entry
 * @param previousAnnotation the previous item annotation (only for annotation entries)
 * @param tag                true for tag entries: {@code id} is the tag and {@code previousFile} its tag file
 * @param previousTagEdit    the previous tag edit (only for tag entries)
 */
public record HistoryEntry(long batch, boolean annotation, ResourceLocation id,
                           Optional<String> previousFile, Optional<ChangeEntry> previousChange,
                           Optional<Annotation> previousAnnotation, String author, long time,
                           boolean tag, Optional<TagEdit> previousTagEdit) {

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
            TagEdit.CODEC.optionalFieldOf("previous_tag_edit").forGetter(HistoryEntry::previousTagEdit)
    ).apply(i, HistoryEntry::new));
}
