package dev.remold.server;

import com.mojang.brigadier.CommandDispatcher;
import dev.remold.Remold;
import dev.remold.network.Payloads;
import dev.remold.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

/**
 * <pre>
 * /remold          open the editor
 * /remold apply    reload datapacks so saved changes take effect (and re-read config/remold/remold.json)
 * /remold undo     undo the most recent change
 * /remold list     list every change made with Remold
 * /remold export   export a shareable datapack zip + KubeJS script
 * </pre>
 */
public final class RemoldCommands {
    private RemoldCommands() {}

    /** Command output is translated; the English fallback covers consoles without Remold's language files. */
    private static MutableComponent msg(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("remold.command." + key, fallback, args);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Remold.MOD_ID)
                .requires(s -> RemoldConfig.get().allowed(s))
                .executes(ctx -> {
                    Services.PLATFORM.sendToPlayer(ctx.getSource().getPlayerOrException(), Payloads.OpenEditor.INSTANCE);
                    return 1;
                })
                .then(Commands.literal("apply").executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    RemoldConfig.load();
                    server.getPlayerList().getPlayers().forEach(ServerActions::sendAccess);
                    RemoldPack.get(server).apply().whenComplete((v, e) -> server.execute(() -> {
                        ctx.getSource().sendSuccess(() -> msg("reloaded", "[Remold] Recipes reloaded").withStyle(ChatFormatting.GREEN), true);
                        ServerActions.broadcastChanges(server);
                    }));
                    return 1;
                }))
                .then(Commands.literal("undo").executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    try {
                        var result = RemoldPack.get(server).undo();
                        if (result.isEmpty()) {
                            ctx.getSource().sendFailure(msg("nothing_to_undo", "[Remold] Nothing to undo"));
                            return 0;
                        }
                        if (result.get().annotations()) ServerActions.broadcastAnnotations(server);
                        Runnable done = () -> {
                            ctx.getSource().sendSuccess(() -> msg("undone", "[Remold] Undid %s change(s) by %s",
                                    result.get().changes(), result.get().author()).withStyle(ChatFormatting.GREEN), true);
                            ServerActions.broadcastChanges(server);
                        };
                        if (result.get().recipes()) {
                            RemoldPack.get(server).apply().whenComplete((v, e) -> server.execute(done));
                        } else {
                            done.run();
                        }
                        return 1;
                    } catch (Exception e) {
                        Remold.LOG.error("Undo failed", e);
                        ctx.getSource().sendFailure(msg("failed", "[Remold] Failed: %s", String.valueOf(e.getMessage())));
                        return 0;
                    }
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    var changes = RemoldPack.get(ctx.getSource().getServer()).changes();
                    ctx.getSource().sendSuccess(() -> msg("count", "[Remold] %s change(s)", changes.size()).withStyle(ChatFormatting.GOLD), false);
                    for (var c : changes) {
                        ChatFormatting color = switch (c.kind()) {
                            case ADDED -> ChatFormatting.GREEN;
                            case REPLACED -> ChatFormatting.YELLOW;
                            case REMOVED -> ChatFormatting.RED;
                        };
                        String kind = c.kind().getSerializedName();
                        MutableComponent line = Component.literal(" ").append(msg("kind." + kind, kind).withStyle(color))
                                .append(Component.literal(" " + c.id()).withStyle(ChatFormatting.WHITE));
                        if (!c.author().isEmpty()) {
                            line.append(Component.literal(" ")).append(msg("by", "by %s", c.author()).withStyle(ChatFormatting.GRAY));
                        }
                        ctx.getSource().sendSuccess(() -> line, false);
                    }
                    return changes.size();
                }))
                .then(Commands.literal("export").executes(ctx -> {
                    try {
                        var result = Exporter.export(ctx.getSource().getServer());
                        String path = result.zip().toAbsolutePath().toString();
                        Component file = Component.literal(result.zip().getFileName().toString()).withStyle(s -> s
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, path))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal(path + "\n").append(msg("copy", "Click to copy the path")))));
                        ctx.getSource().sendSuccess(() -> msg("exported", "[Remold] Exported %s and %s",
                                file, result.kubejs().getFileName().toString()).withStyle(ChatFormatting.GREEN), false);
                        return 1;
                    } catch (Exception e) {
                        Remold.LOG.error("Export failed", e);
                        ctx.getSource().sendFailure(msg("export_failed", "[Remold] Export failed: %s", String.valueOf(e.getMessage())));
                        return 0;
                    }
                })));
    }
}
