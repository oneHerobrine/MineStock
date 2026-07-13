package dev.onelili.mstock.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.entity.Entity;
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
}
