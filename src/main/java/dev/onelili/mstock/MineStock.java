package dev.onelili.mstock;

import dev.onelili.mstock.api.MineStockAPI;
import dev.onelili.mstock.command.DynamicCommandRegistration;
import dev.onelili.mstock.command.MStockCommand;
import dev.onelili.mstock.config.DatabaseConfig;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.DatabaseManager;
import dev.onelili.mstock.database.HoldingRepository;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.lifecycle.LifecycleTaskManager;
import dev.onelili.mstock.listener.ChatInputListener;
import dev.onelili.mstock.scheduler.CompatScheduler;
import dev.onelili.mstock.stockio.StockApiService;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.util.LangUtil;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MineStock extends JavaPlugin {

    private MainConfig mainConfig;
    private DatabaseConfig databaseConfig;
    private LangUtil lang;
    private DatabaseManager dbManager;
    private HoldingRepository holdingRepo;
    private EconomyService economy;
    private volatile StockApiService api;
    private ChatInputSession chatSession;
    private MStockCommand commandExecutor;
    private ChatInputListener chatListener;
    private DynamicCommandRegistration stCommandRegistration;
    private LifecycleTaskManager taskManager;
    private final AtomicBoolean operational = new AtomicBoolean();

    @Override
    public void onEnable() {
        try {
            operational.set(false);
            getLogger().info("运行环境: " + (CompatScheduler.isFolia() ? "Folia" : "Paper/Spigot"));

            taskManager = new LifecycleTaskManager("MineStock");
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

            PluginCommand stockCmd = getCommand("stock");
            if (stockCmd != null) {
                stockCmd.setExecutor(commandExecutor);
                stockCmd.setTabCompleter(commandExecutor);
            }

            CommandMap commandMap = getServer().getCommandMap();
            stCommandRegistration = new DynamicCommandRegistration(commandMap);
            if (stCommandRegistration.register(
                    "minestock", new StProxyCommand(this, commandExecutor))) {
                getLogger().info("[MineStock] 已动态注册 /st 命令");
            } else {
                getLogger().info("[MineStock] /st 已被其他插件占用，跳过注册");
            }

            chatListener = new ChatInputListener(this, chatSession);
            getServer().getPluginManager().registerEvents(chatListener, this);

            MineStockAPI.init(this);
            operational.set(true);

            getLogger().info("MineStock 插件已启用");
        } catch (Exception e) {
            operational.set(false);
            getLogger().severe("MineStock 初始化失败: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        operational.set(false);
        cleanup("关闭公开 API", MineStockAPI::shutdown);

        if (stCommandRegistration != null) {
            DynamicCommandRegistration currentRegistration = stCommandRegistration;
            stCommandRegistration = null;
            cleanup("注销 /st 命令", currentRegistration::close);
        }

        if (chatListener != null) {
            ChatInputListener currentListener = chatListener;
            chatListener = null;
            cleanup("注销聊天监听器", () -> HandlerList.unregisterAll(currentListener));
        }
        if (chatSession != null) cleanup("清空聊天会话", chatSession::clearAll);

        StockApiService currentApi = api;
        api = null;
        if (currentApi != null) cleanup("关闭行情服务", currentApi::close);

        LifecycleTaskManager currentTasks = taskManager;
        taskManager = null;
        if (currentTasks != null) {
            cleanup("停止异步任务", () -> {
                try {
                    if (!currentTasks.shutdown(Duration.ofSeconds(10))) {
                        getLogger().severe("等待关键交易结算超时，已强制终止剩余任务");
                    }
                } finally {
                    currentTasks.runDeferredShutdownActions(error ->
                            getLogger().severe("执行交易补偿失败: " + error.getMessage()));
                }
            });
        }

        DatabaseManager currentDatabase = dbManager;
        dbManager = null;
        if (currentDatabase != null) cleanup("关闭数据库", currentDatabase::close);

        cleanup("重置静态命令", this::resetStaticCommands);

        mainConfig = null;
        databaseConfig = null;
        lang = null;
        holdingRepo = null;
        economy = null;
        chatSession = null;
        commandExecutor = null;
        getLogger().info("MineStock 插件已禁用");
    }

    private void resetStaticCommands() {
        PluginCommand mstock = getCommand("mstock");
        if (mstock != null) {
            mstock.setExecutor(null);
            mstock.setTabCompleter(null);
        }
        PluginCommand stock = getCommand("stock");
        if (stock != null) {
            stock.setExecutor(null);
            stock.setTabCompleter(null);
        }
    }

    private void cleanup(String action, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            getLogger().warning(action + "失败: " + e.getMessage());
        }
    }

    public MainConfig getMainConfig() { return mainConfig; }
    public LangUtil getLang() { return lang; }
    public HoldingRepository getHoldingRepo() { return holdingRepo; }
    public EconomyService getEconomy() { return economy; }
    public StockApiService getApi() { return api; }
    public ChatInputSession getChatSession() { return chatSession; }
    public MStockCommand getCommandExecutor() { return commandExecutor; }
    public LifecycleTaskManager getTaskManager() { return taskManager; }
    public boolean isOperational() { return operational.get() && isEnabled(); }

    public synchronized void reload() {
        if (!isOperational()) throw new IllegalStateException("MineStock is not active");

        reloadConfig();
        MainConfig newConfig = new MainConfig(this);
        LangUtil newLang = new LangUtil(this);
        StockApiService newApi = new StockApiService(newConfig, getLogger());

        StockApiService oldApi = api;
        mainConfig = newConfig;
        lang = newLang;
        api = newApi;
        if (commandExecutor != null) commandExecutor.refresh(newApi, newConfig, newLang);
        if (oldApi != null) oldApi.close();
        getLogger().info("MineStock 配置已重载");
    }

    private static final class StProxyCommand extends org.bukkit.command.Command
            implements PluginIdentifiableCommand {

        private final MineStock plugin;
        private final MStockCommand delegate;

        StProxyCommand(MineStock plugin, MStockCommand delegate) {
            super("st");
            this.plugin = plugin;
            this.delegate = delegate;
            setDescription("股票市场命令");
            setUsage("/st [recommended|<代码>|buy|sell|portfolio]");
            setPermission("minestock.use");
        }

        @Override
        public boolean execute(
                @org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                @org.jetbrains.annotations.NotNull String label,
                @org.jetbrains.annotations.NotNull String[] args) {
            if (!testPermission(sender)) return true;
            return delegate.onCommand(sender, this, label, args);
        }

        @Override
        public @org.jetbrains.annotations.NotNull java.util.List<String> tabComplete(
                @org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                @org.jetbrains.annotations.NotNull String alias,
                @org.jetbrains.annotations.NotNull String[] args) {
            if (!testPermissionSilent(sender)) return java.util.List.of();
            java.util.List<String> result = delegate.onTabComplete(sender, this, alias, args);
            return result != null ? result : java.util.List.of();
        }

        @Override
        public @org.jetbrains.annotations.NotNull Plugin getPlugin() {
            return plugin;
        }
    }
}
