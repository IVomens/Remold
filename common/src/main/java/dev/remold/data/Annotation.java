package dev.remold.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * Extra information attached to an item by an operator, e.g. a red "ITEM UNOBTAINABLE" tooltip.
 *
 * @param lines            tooltip lines, using {@code &} colour codes (see {@link TextUtil})
 * @param hideFromViewers  whether recipe viewers (JEI, EMI, ...) should hide the item
 */
public record Annotation(List<String> lines, boolean hideFromViewers) {
    public static final Annotation EMPTY = new Annotation(List.of(), false);

    public static final Codec<Annotation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("lines", List.of()).forGetter(Annotation::lines),
            Codec.BOOL.optionalFieldOf("hide_from_viewers", false).forGetter(Annotation::hideFromViewers)
    ).apply(i, Annotation::new));

    public static final StreamCodec<ByteBuf, Annotation> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(16)), Annotation::lines,
            ByteBufCodecs.BOOL, Annotation::hideFromViewers,
            Annotation::new);

    public Annotation {
        lines = lines.stream().filter(s -> !s.isBlank()).toList();
    }

    public boolean isEmpty() {
        return lines.isEmpty() && !hideFromViewers;
    }
}
