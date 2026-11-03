package dev.remold.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record TagEdit(List<String> add, List<String> remove) {
    public static final TagEdit EMPTY = new TagEdit(List.of(), List.of());

    public static final Codec<TagEdit> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("add", List.of()).forGetter(TagEdit::add),
            Codec.STRING.listOf().optionalFieldOf("remove", List.of()).forGetter(TagEdit::remove)
    ).apply(i, TagEdit::new));

    public static final StreamCodec<ByteBuf, TagEdit> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TagEdit::add,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TagEdit::remove,
            TagEdit::new);

    public TagEdit {
        add = List.copyOf(add);
        remove = List.copyOf(remove);
    }

    public boolean isEmpty() {
        return add.isEmpty() && remove.isEmpty();
    }
}
