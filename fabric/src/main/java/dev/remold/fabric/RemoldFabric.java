package dev.remold.fabric;

import dev.remold.network.Payloads;
import dev.remold.server.RemoldCommands;
import dev.remold.server.RemoldPack;
import dev.remold.server.ServerActions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

/** Fabric entry point: wires Fabric API events to the loader-agnostic code in {@code dev.remold}. */
public class RemoldFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        registerPayloads();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> RemoldCommands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerActions.onLogin(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> RemoldPack.onServerStarting());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RemoldPack.onServerStopped());
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(RemoldPack::onServerTick);
    }

    private static void registerPayloads() {
        toServer(Payloads.SaveRecipe.TYPE, Payloads.SaveRecipe.CODEC, ServerActions::handleSave);
        toServer(Payloads.RecipeAction.TYPE, Payloads.RecipeAction.CODEC, ServerActions::handleAction);
        toServer(Payloads.SetAnnotation.TYPE, Payloads.SetAnnotation.CODEC, ServerActions::handleAnnotation);
        toServer(Payloads.SetTag.TYPE, Payloads.SetTag.CODEC, ServerActions::handleTag);
        toServer(Payloads.Bulk.TYPE, Payloads.Bulk.CODEC, ServerActions::handleBulk);
        toServer(Payloads.SetGates.TYPE, Payloads.SetGates.CODEC, ServerActions::handleGates);
        toServer(Payloads.Unify.TYPE, Payloads.Unify.CODEC, ServerActions::handleUnify);
        toServer(Payloads.ImportRequest.TYPE, Payloads.ImportRequest.CODEC, ServerActions::handleImport);
        toServer(Payloads.RequestTemplate.TYPE, Payloads.RequestTemplate.CODEC, ServerActions::handleTemplateRequest);

        // Client-bound types are registered on both sides; their handlers are set in RemoldFabricClient.
        PayloadTypeRegistry.playS2C().register(Payloads.OpenEditor.TYPE, Payloads.OpenEditor.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.SyncAnnotations.TYPE, Payloads.SyncAnnotations.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.SyncChanges.TYPE, Payloads.SyncChanges.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.TemplateJson.TYPE, Payloads.TemplateJson.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.SyncGates.TYPE, Payloads.SyncGates.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.ImportList.TYPE, Payloads.ImportList.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Access.TYPE, Payloads.Access.CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Notice.TYPE, Payloads.Notice.CODEC);
    }

    private static <T extends CustomPacketPayload> void toServer(CustomPacketPayload.Type<T> type,
                                                                 StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                                 BiConsumer<ServerPlayer, T> handler) {
        PayloadTypeRegistry.playC2S().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                context.server().execute(() -> handler.accept(context.player(), payload)));
    }
}
