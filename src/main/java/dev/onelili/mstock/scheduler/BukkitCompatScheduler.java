package dev.onelili.mstock.scheduler;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

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
}
