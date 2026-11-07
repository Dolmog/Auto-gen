package fr.chunkyautogen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cerveau du mod. Tout se passe sur le thread serveur, une évaluation par seconde.
 *
 * File : triée par date de déconnexion décroissante (la plus récente d'abord).
 * Une seule zone est active à la fois. Si une zone plus récente que l'active apparaît, l'active est mise en pause,
 * attend que Chunky l'ait relâchée, puis retourne dans la file. Quand elle sera reprise, elle repartira de zéro :
 * Chunky saute rapidement les chunks déjà générés, rien n'est perdu.
 *
 * Phases de la zone active :
 * RUNNING   : la tâche tourne dans Chunky.
 * STOPPING  : pause demandée, on attend que Chunky relâche la tâche (la pause de Chunky est asynchrone).
 * SUSPENDED : en pause (par nous), la tâche sauvegardée par Chunky peut être reprise avec "continue".
 * RESTORED  : relue depuis le fichier d'état après un redémarrage, pas encore reprise.
 */
public final class ZoneManager {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int STARTUP_GRACE_TICKS = 200; // laisse Chunky finir son propre démarrage (continue-on-restart)

    private enum Phase { RUNNING, STOPPING, SUSPENDED, RESTORED }

    private enum AfterStop { PAUSE, REQUEUE }

    /** Contenu du fichier d'état. Public pour Gson. */
    public static final class State {
        public Zone active;
        public List<Zone> queue = new ArrayList<>();
        public List<Zone> completed = new ArrayList<>();
    }

    private final MinecraftServer server;
    private final ChunkyBridge chunky;
    private final Path file;

    private final List<Zone> queue = new ArrayList<>();
    private final List<Zone> completed = new ArrayList<>();
    private Zone active;
    private Phase phase;
    private AfterStop afterStop;

    private boolean stopping;
    private long tick;
    private long emptySince = -1;

    public ZoneManager(MinecraftServer server) throws ReflectiveOperationException {
        this.server = server;
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("chunkyautogen_state.json");
        load();
        this.chunky = ChunkyBridge.create();
        LOG.info("Chunky AutoGen prêt : {} zone(s) en file, {} déjà terminée(s){}.",
                queue.size(), completed.size(), active != null ? ", reprise de la zone " + active : "");
    }

    // ------------------------------------------------------------------ événements

    /** Les déconnexions pendant l'arrêt du serveur sont aussi enregistrées (pas d'appel à Chunky ici). */
    public void onDisconnect(ServerPlayer player) {
        String dim = player.level().dimension().location().toString();
        if (!Config.DIMENSIONS.get().contains(dim)) return;

        Zone zone = new Zone(dim, player.getBlockX(), player.getBlockZ(), Config.RADIUS.get(), System.currentTimeMillis());
        double dedupe = Config.DEDUPE_DISTANCE.get();

        if (covers(active, zone, dedupe)) return;
        for (Zone c : completed) if (covers(c, zone, dedupe)) return;

        // Une zone proche déjà en attente est remplacée par la nouvelle (qui remonte en tête).
        queue.removeIf(q -> q.dimension.equals(dim) && q.distanceTo(zone) < dedupe);
        insertSorted(zone);
        while (queue.size() > Config.MAX_QUEUE.get()) queue.remove(queue.size() - 1);

        LOG.info("Zone ajoutée en tête de file : {}", zone);
        save();
    }

    /** Le serveur s'arrête : Chunky pause et sauvegarde ses tâches lui-même, on ne touche plus à rien. */
    public void markStopping() {
        stopping = true;
    }

    public void shutdown() {
        save();
    }

    public void tick() {
        tick++;
        if (stopping || tick % 200 != 0) return; // une évaluation par seconde

        int players = server.getPlayerList().getPlayerCount();
        if (players == 0) {
            if (emptySince < 0) emptySince = tick;
        } else {
            emptySince = -1;
        }
        if (tick < STARTUP_GRACE_TICKS) return;

        boolean allowed = !Config.ONLY_WHEN_EMPTY.get()
                || (players == 0 && tick - emptySince >= Config.RESUME_DELAY_SECONDS.get() * 20L);

        if (active != null && !updateActive(allowed)) return;
        if (active == null && allowed) startNext();
    }

    // ------------------------------------------------------------------ logique interne

    /** @return true si la zone active a été libérée et qu'on peut en démarrer une autre dans ce même passage. */
    private boolean updateActive(boolean allowed) {
        String dim = active.dimension;
        boolean inChunky = chunky.isRunning(dim);

        switch (phase) {
            case STOPPING -> {
                if (inChunky) return false; // Chunky termine encore son arrêt
                if (afterStop == AfterStop.REQUEUE) {
                    requeueActive();
                    return true;
                }
                phase = Phase.SUSPENDED;
                return false;
            }
            case RUNNING -> {
                if (!inChunky) {
                    // La tâche a quitté Chunky sans que nous l'ayons demandé : terminée, annulée, ou mise en pause à la main.
                    // continue réussit seulement dans le dernier cas (tâche sauvegardée non annulée).
                    if (chunky.resume(dim)) {
                        LOG.info("Tâche mise en pause par un tiers, reprise : {}", active);
                        return false;
                    }
                    LOG.info("Zone terminée : {}", active);
                    finishActive();
                    return true;
                }
                if (!allowed) {
                    requestStop(AfterStop.PAUSE);
                } else if (newerWaiting()) {
                    requestStop(AfterStop.REQUEUE);
                }
                return false;
            }
            case SUSPENDED, RESTORED -> {
                if (inChunky) {
                    // Chunky l'a relancée lui-même (continue-on-restart ou /chunky continue) : on l'adopte.
                    phase = Phase.RUNNING;
                    LOG.info("Tâche Chunky déjà relancée, adoptée : {}", active);
                    return false;
                }
                if (!allowed) return false;
                if (newerWaiting()) {
                    requeueActive();
                    return true;
                }
                if (foreignRunning()) return false;
                if (chunky.resume(dim)) {
                    phase = Phase.RUNNING;
                    LOG.info("Génération reprise : {}", active);
                    save();
                    return false;
                }
                if (phase == Phase.SUSPENDED) {
                    // Plus de tâche sauvegardée à reprendre : elle s'est terminée pendant la pause.
                    LOG.info("Zone terminée pendant la pause : {}", active);
                    finishActive();
                    return true;
                }
                // RESTORED sans tâche Chunky sauvegardée (crash, ou terminée avant l'arrêt) : on repart de zéro,
                // les chunks existants sont sautés rapidement.
                Zone z = active;
                active = null;
                phase = null;
                if (!startZone(z)) {
                    LOG.warn("Impossible de redémarrer {} : zone abandonnée.", z);
                    save();
                    return true;
                }
                save();
                return false;
            }
        }
        return false;
    }

    private void requestStop(AfterStop after) {
        LOG.info(after == AfterStop.PAUSE ? "Génération mise en pause : {}" : "Zone plus récente en attente, pause de : {}", active);
        chunky.pause(active.dimension);
        phase = Phase.STOPPING;
        afterStop = after;
    }

    private void requeueActive() {
        LOG.info("{} est remise en file.", active);
        insertSorted(active);
        active = null;
        phase = null;
        save();
    }

    private boolean newerWaiting() {
        return !queue.isEmpty() && queue.get(0).queuedAt > active.queuedAt;
    }

    private void startNext() {
        if (queue.isEmpty() || foreignRunning()) return; // foreign : tâche Chunky lancée à la main, on attend
        Zone z = queue.remove(0);
        if (!startZone(z)) LOG.warn("Impossible de démarrer {} : zone abandonnée.", z);
        save();
    }

    private boolean startZone(Zone z) {
        if (!chunky.start(z, Config.PATTERN.get())) return false;
        active = z;
        phase = Phase.RUNNING;
        LOG.info("Génération démarrée : {}", z);
        return true;
    }

    /** Une tâche Chunky tourne dans un monde alors que nous n'avons pas de zone active qui la justifie. */
    private boolean foreignRunning() {
        for (ServerLevel level : server.getAllLevels()) {
            if (chunky.isRunning(level.dimension().location().toString())) return true;
        }
        return false;
    }

    private void finishActive() {
        completed.add(0, active);
        while (completed.size() > Config.MAX_COMPLETED.get()) completed.remove(completed.size() - 1);
        active = null;
        phase = null;
        save();
    }

    private static boolean covers(Zone existing, Zone candidate, double dedupe) {
        return existing != null
                && existing.dimension.equals(candidate.dimension)
                && existing.radius >= candidate.radius
                && existing.distanceTo(candidate) < dedupe;
    }

    private void insertSorted(Zone z) {
        int i = 0;
        while (i < queue.size() && queue.get(i).queuedAt > z.queuedAt) i++;
        queue.add(i, z);
    }

    // ------------------------------------------------------------------ commandes

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Active : " + (active == null ? "aucune" : active + " [" + phase + "]"));
        lines.add("En attente (" + queue.size() + ") :");
        for (int i = 0; i < queue.size(); i++) lines.add("  " + (i + 1) + ". " + queue.get(i));
        lines.add("Terminées mémorisées : " + completed.size());
        return lines;
    }

    public int clearQueue() {
        int n = queue.size();
        queue.clear();
        save();
        return n;
    }

    // ------------------------------------------------------------------ persistance

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            State s = GSON.fromJson(r, State.class);
            if (s == null) return;
            if (s.queue != null) queue.addAll(s.queue);
            if (s.completed != null) completed.addAll(s.completed);
            queue.sort(Comparator.comparingLong((Zone z) -> z.queuedAt).reversed());
            if (s.active != null) {
                active = s.active;
                phase = Phase.RESTORED;
            }
        } catch (Exception e) {
            LOG.error("Lecture de {} impossible, démarrage avec une file vide.", file, e);
        }
    }

    private void save() {
        State s = new State();
        s.active = active;
        s.queue = queue;
        s.completed = completed;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) {
            GSON.toJson(s, w);
        } catch (IOException e) {
            LOG.error("Écriture de {} impossible", tmp, e);
            return;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOG.error("Déplacement de {} impossible", tmp, e2);
            }
        }
    }
}
