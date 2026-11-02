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

/**
 * <pre>
 * /remold          open the editor
 * /remold apply    reload datapacks so saved changes take effect
 * /remold list     list every change made with Remold
 * /remold export   export a shareable datapack zip + KubeJS script
 * </pre>
 */
public final class RemoldCommands {
    private RemoldCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Remold.MOD_ID)
                .requires(s -> s.hasPermission(Remold.PERMISSION_LEVEL))
                .executes(ctx -> {
                    Services.PLATFORM.sendToPlayer(ctx.getSource().getPlayerOrException(), Payloads.OpenEditor.INSTANCE);
                    return 1;
                })
                .then(Commands.literal("apply").executes(ctx -> {
                    RemoldPack.get(ctx.getSource().getServer()).apply().whenComplete((v, e) -> ctx.getSource().getServer().execute(() -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("[Remold] Recipes reloaded").withStyle(ChatFormatting.GREEN), true);
                        ServerActions.broadcastChanges(ctx.getSource().getServer());
                    }));
                    return 1;
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    var changes = RemoldPack.get(ctx.getSource().getServer()).changes();
                    ctx.getSource().sendSuccess(() -> Component.literal("[Remold] " + changes.size() + " change(s)").withStyle(ChatFormatting.GOLD), false);
                    for (var c : changes) {
                        ChatFormatting color = switch (c.kind()) {
                            case ADDED -> ChatFormatting.GREEN;
                            case REPLACED -> ChatFormatting.YELLOW;
                            case REMOVED -> ChatFormatting.RED;
                        };
                        ctx.getSource().sendSuccess(() -> Component.literal(" " + c.kind().getSerializedName() + " ").withStyle(color)
                                .append(Component.literal(c.id().toString()).withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(c.author().isEmpty() ? "" : " by " + c.author()).withStyle(ChatFormatting.GRAY)), false);
                    }
                    return changes.size();
                }))
                .then(Commands.literal("export").executes(ctx -> {
                    try {
                        var result = Exporter.export(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal("[Remold] Exported ")
                                .withStyle(ChatFormatting.GREEN)
                                .append(Component.literal(result.zip().getFileName().toString()).withStyle(s -> s
                                        .withUnderlined(true)
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, result.zip().getParent().toAbsolutePath().toString()))))
                                .append(Component.literal(" and " + result.kubejs().getFileName()).withStyle(ChatFormatting.GREEN)), false);
                        return 1;
                    } catch (Exception e) {
                        Remold.LOG.error("Export failed", e);
                        ctx.getSource().sendFailure(Component.literal("[Remold] Export failed: " + e.getMessage()));
                        return 0;
                    }
                })));
    }
}
