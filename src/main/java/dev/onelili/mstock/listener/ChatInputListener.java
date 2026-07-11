package dev.onelili.mstock.listener;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.ui.PendingAction;
import dev.onelili.mstock.util.LangUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatInputListener implements Listener {
    private final MineStock plugin;
    private final ChatInputSession session;

    public ChatInputListener(MineStock plugin, ChatInputSession session) {
        this.plugin = plugin;
        this.session = session;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingAction action = session.getSession(player.getUniqueId());
        if (action == null) return;

        event.setCancelled(true);
        String input = event.getMessage().trim();

        if ("取消".equalsIgnoreCase(input) || "cancel".equalsIgnoreCase(input)) {
            session.clearSession(player.getUniqueId());
            plugin.getLang().send(player, "input-cancelled");
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(input);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getLang().send(player, "input-invalid");
            return;
        }

        // Store the amount and move the session to the confirm-waiting stage.
        action.setAmount(amount);

        // Fetch price on async thread (we are already async here), then send the confirm preview on main thread.
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getCommandExecutor().showTradeConfirm(player, action));
    }
}
