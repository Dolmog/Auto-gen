package fr.chunkyautogen;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue RADIUS;
    public static final ModConfigSpec.BooleanValue ONLY_WHEN_EMPTY;
    public static final ModConfigSpec.IntValue RESUME_DELAY_SECONDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS;
    public static final ModConfigSpec.IntValue DEDUPE_DISTANCE;
    public static final ModConfigSpec.IntValue MAX_QUEUE;
    public static final ModConfigSpec.IntValue MAX_COMPLETED;
    public static final ModConfigSpec.ConfigValue<String> PATTERN;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        RADIUS = b.comment("Rayon (en blocs) du cercle généré autour de la position de déconnexion d'un joueur.")
                .defineInRange("radius", 2000, 64, 1_000_000);

        ONLY_WHEN_EMPTY = b.comment(
                        "Si true, la génération ne tourne que lorsque plus aucun joueur n'est connecté",
                        "(elle est mise en pause dès qu'un joueur se connecte).")
                .define("onlyWhenServerEmpty", true);

        RESUME_DELAY_SECONDS = b.comment(
                        "Délai (secondes) après le départ du dernier joueur avant de (re)lancer la génération.",
                        "Évite de relancer pour une simple reconnexion rapide.")
                .defineInRange("resumeDelaySeconds", 10, 0, 3600);

        DIMENSIONS = b.comment("Dimensions pour lesquelles une déconnexion crée une zone à générer.")
                .defineListAllowEmpty("dimensions", List.of("minecraft:overworld"),
                        () -> "minecraft:overworld", o -> o instanceof String);

        DEDUPE_DISTANCE = b.comment(
                        "Une déconnexion à moins de N blocs d'une zone déjà en file / en cours / terminée",
                        "(dans la même dimension) ne crée pas de nouvelle zone.")
                .defineInRange("dedupeDistance", 500, 0, 1_000_000);

        MAX_QUEUE = b.comment("Nombre maximum de zones en attente. Les plus anciennes sont abandonnées.")
                .defineInRange("maxQueueSize", 20, 1, 1000);

        MAX_COMPLETED = b.comment("Nombre de zones terminées mémorisées (pour ne pas les régénérer).")
                .defineInRange("maxCompletedHistory", 50, 0, 1000);

        PATTERN = b.comment("Pattern de génération de Chunky (concentric, loop, spiral, region, csv, world...).")
                .define("pattern", "concentric");

        SPEC = b.build();
    }

    private Config() {}
}
