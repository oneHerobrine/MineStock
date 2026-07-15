package dev.onelili.mstock.scheduler;

import org.bukkit.entity.Entity;
import org.bukkit.Location;
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

    /** Runs a task on the thread that owns the supplied block location. */
    public abstract void runAtLocation(Plugin plugin, Location location, Runnable task);

    /** Runs a delayed task on the thread that owns the supplied block location. */
    public abstract void runAtLocationLater(Plugin plugin, Location location, Runnable task, long delayTicks);

    /** Starts a repeating background task and returns a cancellation handle. */
    public abstract ScheduledTaskHandle runAsyncTimer(
            Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks);

    @FunctionalInterface
    public interface ScheduledTaskHandle {
        void cancel();
    }
}
