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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.StringArgumentType;

public final class RemoldCommands {
    private RemoldCommands() {}

    private static MutableComponent msg(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("remold.command." + key, fallback, args);
    }

    private static int stage(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean grant) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String stage = StringArgumentType.getString(ctx, "stage");
        int changed = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(ctx, "players")) {
            try {
                if (RemoldPack.get(ctx.getSource().getServer()).setStage(player.getUUID(), stage, grant)) changed++;
            } catch (java.io.IOException e) {
                ctx.getSource().sendFailure(msg("failed", "[Remold] Failed: %s", String.valueOf(e.getMessage())));
                return 0;
            }
            Gates.sync(player);
        }
        int count = changed;
        ctx.getSource().sendSuccess(() -> msg(grant ? "stage_granted" : "stage_revoked",
                grant ? "[Remold] Granted stage %s to %s player(s)" : "[Remold] Revoked stage %s from %s player(s)", stage, count), true);
        return changed;
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
                .then(Commands.literal("stage")
                        .then(Commands.literal("grant").then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("stage", StringArgumentType.word()).executes(ctx -> stage(ctx, true)))))
                        .then(Commands.literal("revoke").then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.argument("stage", StringArgumentType.word()).executes(ctx -> stage(ctx, false)))))
                        .then(Commands.literal("list").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            var stages = RemoldPack.get(ctx.getSource().getServer()).stages(player.getUUID());
                            ctx.getSource().sendSuccess(() -> msg("stages", "[Remold] %s has stages: %s",
                                    player.getDisplayName(), stages.isEmpty() ? "-" : String.join(", ", new java.util.TreeSet<>(stages))), false);
                            return stages.size();
                        }))))
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
