package dev.onelili.mstock.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.entity.Entity;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/** Folia implementation: uses {@code EntityScheduler} and {@code AsyncScheduler}. */
final class FoliaScheduler extends CompatScheduler {

    @Override
    public void runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        EntityScheduler scheduler = entity.getScheduler();
        // retired runnable: entity was removed before task ran — nothing to do
        scheduler.run(plugin, t -> task.run(), null);
    }

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        AsyncScheduler async = plugin.getServer().getAsyncScheduler();
        async.runNow(plugin, t -> task.run());
    }

    @Override
    public void runAtLocation(Plugin plugin, Location location, Runnable task) {
        plugin.getServer().getRegionScheduler().execute(plugin, location, task);
    }

    @Override
    public void runAtLocationLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        plugin.getServer().getRegionScheduler()
                .runDelayed(plugin, location, ignored -> task.run(), delayTicks);
    }

    @Override
    public ScheduledTaskHandle runAsyncTimer(
            Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        long initialMillis = Math.max(50L, initialDelayTicks * 50L);
        long periodMillis = Math.max(50L, periodTicks * 50L);
        var scheduled = plugin.getServer().getAsyncScheduler()
                .runAtFixedRate(plugin, ignored -> task.run(), initialMillis, periodMillis,
                        TimeUnit.MILLISECONDS);
        return scheduled::cancel;
    }
}
