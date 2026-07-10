package dev.onelili.mstock.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.util.logging.Logger;

public class EconomyService {
    private Economy economy;
    private final Logger logger;

    public EconomyService(ServicesManager services, Logger logger) {
        this.logger = logger;
        RegisteredServiceProvider<Economy> rsp = services.getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        } else {
            logger.severe("[MineStock] 未找到 Vault Economy 提供者！");
        }
    }

    public boolean isReady() {
        return economy != null;
    }

    public double getBalance(Player player) {
        return economy.getBalance(player);
    }

    public boolean has(Player player, double amount) {
        return economy.has(player, amount);
    }

    /** 扣款，返回是否成功 */
    public boolean withdraw(Player player, double amount) {
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        return resp.transactionSuccess();
    }

    /** 收款，返回是否成功 */
    public boolean deposit(Player player, double amount) {
        EconomyResponse resp = economy.depositPlayer(player, amount);
        return resp.transactionSuccess();
    }
}
