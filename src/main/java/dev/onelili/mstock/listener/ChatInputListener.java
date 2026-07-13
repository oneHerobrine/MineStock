package dev.onelili.mstock.listener;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.ui.PendingAction;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class ChatInputListener implements Listener {
    private final MineStock plugin;
    private final ChatInputSession session;

    public ChatInputListener(MineStock plugin, ChatInputSession session) {
        this.plugin = plugin;
        this.session = session;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingAction action = session.getSession(player.getUniqueId());
        if (action == null) return;

        event.setCancelled(true);
        // AsyncChatEvent carries a Component; extract plain text for parsing
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

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

        // Switch to player's region thread (Folia) or main thread (Paper/Spigot) to show confirm.
        CompatScheduler.get().runOnEntity(plugin, player, () ->
                plugin.getCommandExecutor().showTradeConfirm(player, action));
    }
}
