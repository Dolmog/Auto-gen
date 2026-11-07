package fr.chunkyautogen;

/** Une zone circulaire à générer. Champs publics pour la (dé)sérialisation Gson. */
public final class Zone {
    public String dimension;   // ex: "minecraft:overworld" (= nom du monde côté Chunky)
    public int x;
    public int z;
    public int radius;
    public long queuedAt;      // horodatage de la déconnexion (ms) : sert de priorité

    public Zone() {}

    public Zone(String dimension, int x, int z, int radius, long queuedAt) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.queuedAt = queuedAt;
    }

    public double distanceTo(Zone other) {
        return Math.hypot(x - other.x, z - other.z);
    }

    @Override
    public String toString() {
        return dimension + " (" + x + ", " + z + ") r=" + radius;
    }
}
