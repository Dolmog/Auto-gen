package fr.chunkyautogen;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Seul point de contact avec Chunky. Appelle l'API (org.popcraft.chunky.api.ChunkyAPI) par réflexion :
 * pas de dépendance de compilation, et si une signature change il n'y a que ce fichier à adapter.
 *
 * Sémantique de l'API Chunky (vérifiée dans ChunkyAPIImpl / GenerationTask) :
 * - pause : lève un drapeau, la tâche reste dans la table (isRunning = true) jusqu'à la fin de son thread,
 *   puis elle est sauvegardée (non annulée) et retirée.
 * - continue : recharge la tâche sauvegardée du monde. Faux si elle est terminée/annulée, ou encore dans la table.
 * - fin naturelle : la tâche est sauvegardée comme "annulée", donc continue renvoie faux.
 */
public final class ChunkyBridge {
    private static final Logger LOG = LogUtils.getLogger();

    private final Object api;
    private final Method mIsRunning, mStart, mPause, mContinue;

    private ChunkyBridge(Object api, Method isRunning, Method start, Method pause, Method cont) {
        this.api = api;
        this.mIsRunning = isRunning;
        this.mStart = start;
        this.mPause = pause;
        this.mContinue = cont;
    }

    public static ChunkyBridge create() throws ReflectiveOperationException {
        Class<?> provider = Class.forName("org.popcraft.chunky.ChunkyProvider");
        Object chunky = provider.getMethod("get").invoke(null); // renvoie l'instance Chunky, pas l'API
        Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
        Class<?> apiClass = Class.forName("org.popcraft.chunky.api.ChunkyAPI");

        return new ChunkyBridge(
                api,
                apiClass.getMethod("isRunning", String.class),
                apiClass.getMethod("startTask", String.class, String.class,
                        double.class, double.class, double.class, double.class, String.class),
                apiClass.getMethod("pauseTask", String.class),
                apiClass.getMethod("continueTask", String.class));
    }

    /** Une tâche est présente dans Chunky pour ce monde (en cours, ou en train de s'arrêter après une pause). */
    public boolean isRunning(String world) {
        return call(mIsRunning, world);
    }

    public boolean start(Zone z, String pattern) {
        return call(mStart, z.dimension, "circle",
                (double) z.x, (double) z.z, (double) z.radius, (double) z.radius, pattern);
    }

    /** Asynchrone : la tâche ne disparaît de isRunning que quelques instants plus tard. */
    public boolean pause(String world) {
        return call(mPause, world);
    }

    public boolean resume(String world) {
        return call(mContinue, world);
    }

    private boolean call(Method m, Object... args) {
        try {
            return (Boolean) m.invoke(api, args);
        } catch (Throwable t) {
            LOG.error("Appel Chunky {} en échec", m.getName(), t);
            return false;
        }
    }
}
