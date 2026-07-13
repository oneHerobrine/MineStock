package dev.onelili.mstock.scheduler;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Unified scheduler abstraction that works on both Paper and Folia.
 *
 * <p>On Paper/Spigot: delegates to {@code BukkitScheduler}.
 * <p>On Folia: uses {@code EntityScheduler} for entity-bound tasks and
 * {@code AsyncScheduler} for background work.
 *
 * <p>Obtain the singleton via {@link #get()}.
 */
public abstract class CompatScheduler {

    private static final CompatScheduler INSTANCE;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        INSTANCE = folia ? new FoliaScheduler() : new BukkitCompatScheduler();
    }

    public static CompatScheduler get() { return INSTANCE; }

    /** Returns {@code true} when running on a Folia server. */
    public static boolean isFolia() { return INSTANCE instanceof FoliaScheduler; }

    /**
     * Runs {@code task} on the region thread that owns {@code entity}'s current chunk.
     * On Paper this is equivalent to running on the main server thread.
     */
    public abstract void runOnEntity(Plugin plugin, Entity entity, Runnable task);

    /**
     * Runs {@code task} on an async (background) thread.
     * Safe to call from any thread.
     */
    public abstract void runAsync(Plugin plugin, Runnable task);
}
