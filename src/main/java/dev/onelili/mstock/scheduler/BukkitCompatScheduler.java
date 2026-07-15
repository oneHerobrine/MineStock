package dev.onelili.mstock.scheduler;

import org.bukkit.entity.Entity;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Paper / Spigot implementation: delegates to {@code BukkitScheduler}. */
final class BukkitCompatScheduler extends CompatScheduler {

    @Override
    public void runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    @Override
    public void runAsync(Plugin plugin, Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runAtLocation(Plugin plugin, Location location, Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    @Override
    public void runAtLocationLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public ScheduledTaskHandle runAsyncTimer(
            Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        BukkitTask scheduled = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, task, initialDelayTicks, periodTicks);
        return scheduled::cancel;
    }
}
