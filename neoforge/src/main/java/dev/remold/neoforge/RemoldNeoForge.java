package dev.remold.neoforge;

import dev.remold.Remold;
import dev.remold.client.RemoldClient;
import dev.remold.network.Payloads;
import dev.remold.server.RemoldCommands;
import dev.remold.server.RemoldPack;
import dev.remold.server.ServerActions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** NeoForge entry point: wires loader events to the loader-agnostic code in {@code dev.remold}. */
@Mod(Remold.MOD_ID)
public class RemoldNeoForge {

    public RemoldNeoForge(IEventBus modBus, Dist dist) {
        modBus.addListener(RemoldNeoForge::registerPayloads);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> RemoldCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) ServerActions.onLogin(player);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> RemoldPack.onServerStopped());
        if (dist.isClient()) {
            RemoldNeoForgeClient.init(modBus);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional on both sides: vanilla clients can join servers with Remold (they just can't edit),
        // and Remold clients can join servers without it.
        PayloadRegistrar registrar = event.registrar("1").optional();

        registrar.playToServer(Payloads.SaveRecipe.TYPE, Payloads.SaveRecipe.CODEC,
                (p, ctx) -> ServerActions.handleSave((ServerPlayer) ctx.player(), p));
        registrar.playToServer(Payloads.RecipeAction.TYPE, Payloads.RecipeAction.CODEC,
                (p, ctx) -> ServerActions.handleAction((ServerPlayer) ctx.player(), p));
        registrar.playToServer(Payloads.SetAnnotation.TYPE, Payloads.SetAnnotation.CODEC,
                (p, ctx) -> ServerActions.handleAnnotation((ServerPlayer) ctx.player(), p));
        registrar.playToServer(Payloads.RequestTemplate.TYPE, Payloads.RequestTemplate.CODEC,
                (p, ctx) -> ServerActions.handleTemplateRequest((ServerPlayer) ctx.player(), p));

        // Client handlers live in RemoldClient; the lambdas are only invoked on the client.
        registrar.playToClient(Payloads.OpenEditor.TYPE, Payloads.OpenEditor.CODEC, (p, ctx) -> RemoldClient.handle(p));
        registrar.playToClient(Payloads.SyncAnnotations.TYPE, Payloads.SyncAnnotations.CODEC, (p, ctx) -> RemoldClient.handle(p));
        registrar.playToClient(Payloads.SyncChanges.TYPE, Payloads.SyncChanges.CODEC, (p, ctx) -> RemoldClient.handle(p));
        registrar.playToClient(Payloads.TemplateJson.TYPE, Payloads.TemplateJson.CODEC, (p, ctx) -> RemoldClient.handle(p));
        registrar.playToClient(Payloads.Notice.TYPE, Payloads.Notice.CODEC, (p, ctx) -> RemoldClient.handle(p));
    }
}
