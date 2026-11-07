package fr.chunkyautogen;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(ChunkyAutoGen.MOD_ID)
public final class ChunkyAutoGen {
    public static final String MOD_ID = "chunkyautogen";
    private static final Logger LOG = LogUtils.getLogger();

    private ZoneManager manager;

    public ChunkyAutoGen(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        try {
            manager = new ZoneManager(event.getServer());
        } catch (Throwable t) {
            manager = null;
            LOG.error("Chunky AutoGen désactivé : impossible de se brancher sur l'API de Chunky.", t);
        }
    }

    // Les joueurs sont déconnectés après ServerStoppingEvent : le manager reste en vie pour enregistrer leurs positions.
    private void onServerStopping(ServerStoppingEvent event) {
        if (manager != null) manager.markStopping();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        if (manager != null) manager.shutdown();
        manager = null;
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (manager != null) manager.tick();
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (manager != null && event.getEntity() instanceof ServerPlayer player) {
            manager.onDisconnect(player);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("chunkyautogen")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> {
                    if (manager == null) {
                        ctx.getSource().sendFailure(Component.literal("Chunky AutoGen n'est pas actif."));
                        return 0;
                    }
                    for (String line : manager.statusLines()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("clear").executes(ctx -> {
                    if (manager == null) {
                        ctx.getSource().sendFailure(Component.literal("Chunky AutoGen n'est pas actif."));
                        return 0;
                    }
                    int n = manager.clearQueue();
                    ctx.getSource().sendSuccess(() -> Component.literal(n + " zone(s) retirée(s) de la file."), true);
                    return Command.SINGLE_SUCCESS;
                })));
    }
}
