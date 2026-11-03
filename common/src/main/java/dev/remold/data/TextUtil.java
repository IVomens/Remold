package dev.remold.data;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Optional;

public final class TextUtil {
    private TextUtil() {}

    public static MutableComponent parse(String input) {
        MutableComponent root = Component.empty();
        Style style = Style.EMPTY.withItalic(false);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char code = input.charAt(i + 1);
                if (code == '#' && isHex(input, i + 2)) {
                    flush(root, current, style);
                    TextColor color = TextColor.parseColor("#" + input.substring(i + 2, i + 8)).result().orElse(null);
                    style = Style.EMPTY.withItalic(false).withColor(color);
                    i += 7;
                    continue;
                }
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(code));
                if (fmt != null) {
                    flush(root, current, style);
                    if (fmt == ChatFormatting.RESET) {
                        style = Style.EMPTY.withItalic(false);
                    } else if (fmt.isColor()) {
                        style = Style.EMPTY.withItalic(false).withColor(fmt);
                    } else {
                        style = style.applyFormat(fmt);
                    }
                    i++;
                    continue;
                }
                if (code == '&') {
                    current.append('&');
                    i++;
                    continue;
                }
            }
            current.append(c);
        }
        flush(root, current, style);
        return root;
    }

    private static boolean isHex(String s, int start) {
        if (start + 6 > s.length()) return false;
        for (int i = start; i < start + 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static void flush(MutableComponent root, StringBuilder text, Style style) {
        if (!text.isEmpty()) {
            root.append(Component.literal(text.toString()).setStyle(style));
            text.setLength(0);
        }
    }

    public static String toCodes(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((style, text) -> {
            if (text.isEmpty()) return Optional.empty();
            TextColor color = style.getColor();
            if (color != null) {
                ChatFormatting named = null;
                for (ChatFormatting f : ChatFormatting.values()) {
                    if (f.isColor() && f.getColor() != null && f.getColor() == color.getValue()) {
                        named = f;
                        break;
                    }
                }
                out.append(named != null ? "&" + named.getChar() : String.format("&#%06x", color.getValue()));
            } else if (!out.isEmpty()) {
                out.append("&r");
            }
            if (style.isBold()) out.append("&l");
            if (style.isItalic()) out.append("&o");
            if (style.isUnderlined()) out.append("&n");
            if (style.isStrikethrough()) out.append("&m");
            if (style.isObfuscated()) out.append("&k");
            out.append(text.replace("&", "&&"));
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }
}
