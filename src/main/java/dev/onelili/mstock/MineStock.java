package dev.onelili.mstock;

import com.tcoded.folialib.FoliaLib;
import dev.onelili.mstock.api.StockApiService;
import dev.onelili.mstock.command.MStockCommand;
import dev.onelili.mstock.config.DatabaseConfig;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.DatabaseManager;
import dev.onelili.mstock.database.HoldingRepository;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.listener.ChatInputListener;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.util.LangUtil;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class MineStock extends JavaPlugin {

    private MainConfig mainConfig;
    private DatabaseConfig databaseConfig;
    private LangUtil lang;
    private DatabaseManager dbManager;
    private HoldingRepository holdingRepo;
    private EconomyService economy;
    private StockApiService api;
    private ChatInputSession chatSession;
    private MStockCommand commandExecutor;
    private FoliaLib foliaLib;

    @Override
    public void onEnable() {
        try {
            foliaLib = new FoliaLib(this);

            mainConfig = new MainConfig(this);
            databaseConfig = new DatabaseConfig(this);
            lang = new LangUtil(this);

            dbManager = new DatabaseManager(getLogger());
            dbManager.init(databaseConfig, getDataFolder());
            holdingRepo = new HoldingRepository(dbManager, getLogger());

            economy = new EconomyService(getServer().getServicesManager(), getLogger());
            if (!economy.isReady()) {
                getLogger().severe("未找到 Vault Economy，插件将禁用");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            api = new StockApiService(mainConfig, getLogger());
            chatSession = new ChatInputSession();

            commandExecutor = new MStockCommand(this);
            PluginCommand cmd = getCommand("mstock");
            if (cmd != null) {
                cmd.setExecutor(commandExecutor);
                cmd.setTabCompleter(commandExecutor);
            }

            getServer().getPluginManager().registerEvents(
                    new ChatInputListener(this, chatSession), this);

            getLogger().info("MineStock 插件已启用");
        } catch (SQLException e) {
            getLogger().severe("数据库初始化失败: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (dbManager != null) {
            dbManager.close();
        }
        getLogger().info("MineStock 插件已禁用");
    }

    public MainConfig getMainConfig() { return mainConfig; }
    public LangUtil getLang() { return lang; }
    public HoldingRepository getHoldingRepo() { return holdingRepo; }
    public EconomyService getEconomy() { return economy; }
    public StockApiService getApi() { return api; }
    public ChatInputSession getChatSession() { return chatSession; }
    public MStockCommand getCommandExecutor() { return commandExecutor; }
    public FoliaLib getFoliaLib() { return foliaLib; }

    public void reload() {
        reloadConfig();
        mainConfig = new MainConfig(this);
        lang = new LangUtil(this);
        api = new StockApiService(mainConfig, getLogger());
        getLogger().info("MineStock 配置已重载");
    }
}
