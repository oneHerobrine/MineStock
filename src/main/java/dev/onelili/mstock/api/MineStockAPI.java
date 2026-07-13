package dev.onelili.mstock.api;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.database.HoldingRepository;
import dev.onelili.mstock.model.Holding;
import dev.onelili.mstock.model.StockInfo;
import dev.onelili.mstock.stockio.StockApiService;
import dev.onelili.mstock.ui.ChatInputSession;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for the MineStock plugin.
 *
 * <h3>Access</h3>
 * <pre>{@code
 * MineStockAPI api = MineStockAPI.getInstance();
 * if (api != null) { ... }
 * }</pre>
 *
 * <h3>Dependency declaration (plugin.yml)</h3>
 * <pre>{@code
 * depend: [MineStock]
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * <ul>
 *   <li>Query methods return {@link CompletableFuture} and run their blocking work on the
 *       common fork-join pool. Callbacks fire on the completing thread; schedule back to the
 *       Bukkit main thread with {@code getServer().getScheduler().runTask(...)} if needed.</li>
 *   <li>Admin methods are <em>synchronous</em> and block the calling thread with JDBC.
 *       Call them from an async context (e.g. {@code runTaskAsynchronously}).</li>
 *   <li>{@link #isSupported}, {@link #hasActiveSession}, {@link #clearSession} are safe to
 *       call from any thread.</li>
 * </ul>
 */
public final class MineStockAPI {

    private static volatile MineStockAPI instance;

    private final MineStock plugin;
    private final StockApiService stockApi;
    private final HoldingRepository holdingRepo;
    private final ChatInputSession chatSession;

    private MineStockAPI(MineStock plugin) {
        this.plugin      = plugin;
        this.stockApi    = plugin.getApi();
        this.holdingRepo = plugin.getHoldingRepo();
        this.chatSession = plugin.getChatSession();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called by {@link MineStock#onEnable()}. Not intended for external use. */
    public static void init(MineStock plugin) {
        instance = new MineStockAPI(plugin);
    }

    /** Called by {@link MineStock#onDisable()}. Not intended for external use. */
    public static void shutdown() {
        instance = null;
    }

    /**
     * Returns the API instance, or {@code null} if MineStock is not enabled.
     * Always null-check the result before use.
     */
    public static MineStockAPI getInstance() {
        return instance;
    }

    // ── Stock info ─────────────────────────────────────────────────────────────

    /**
     * Checks whether the given stock code is recognised by any data source
     * (A-share, HK, or US).
     *
     * @param stockCode case-insensitive stock code
     * @return {@code true} if at least one data source can handle it
     */
    public boolean isSupported(String stockCode) {
        return stockApi.isSupported(stockCode.toUpperCase());
    }

    /**
     * Fetches real-time market data for the given stock code.
     *
     * @param stockCode case-insensitive stock code
     * @return a future that completes with the stock info, or exceptionally if the
     *         code is unsupported or the data source returns an error
     */
    public CompletableFuture<StockInfo> getStockInfo(String stockCode) {
        return stockApi.fetch(stockCode.toUpperCase());
    }

    // ── Holding queries ────────────────────────────────────────────────────────

    /**
     * Retrieves a specific holding for a player, or empty if they don't own any.
     *
     * @param playerUuid the player's UUID
     * @param stockCode  case-insensitive stock code
     * @return a future completing with an {@link Optional} of the holding
     */
    public CompletableFuture<Optional<Holding>> getHolding(UUID playerUuid, String stockCode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.ofNullable(
                        holdingRepo.findByPlayerAndCode(playerUuid, stockCode.toUpperCase()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Retrieves all holdings for a player, sorted by stock code.
     *
     * @param playerUuid the player's UUID
     * @return a future completing with the list (empty if no holdings)
     */
    public CompletableFuture<List<Holding>> getAllHoldings(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return holdingRepo.findAllByPlayer(playerUuid);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Returns whether the player currently holds any shares of the given stock.
     *
     * @param playerUuid the player's UUID
     * @param stockCode  case-insensitive stock code
     * @return a future completing with {@code true} if the holding exists
     */
    public CompletableFuture<Boolean> hasHolding(UUID playerUuid, String stockCode) {
        return getHolding(playerUuid, stockCode).thenApply(Optional::isPresent);
    }

    // ── Admin holding operations ───────────────────────────────────────────────
    // These methods bypass the economy (Vault) and do not fire any events.
    // They are synchronous JDBC calls — always invoke from an async thread.

    /**
     * Forcibly sets a player's holding to the exact amount and average cost given.
     * Existing data is overwritten. If {@code amount <= 0} the holding is deleted.
     *
     * @param playerUuid the player's UUID
     * @param stockCode  case-insensitive stock code
     * @param amount     number of shares (0 or negative to delete)
     * @param avgCost    average cost per share (in-game currency, before price-ratio)
     * @throws SQLException on database error
     */
    public void adminSetHolding(UUID playerUuid, String stockCode,
                                int amount, double avgCost) throws SQLException {
        holdingRepo.adminUpsert(playerUuid, stockCode.toUpperCase(), amount, avgCost);
    }

    /**
     * Deletes all holdings for the specified player.
     *
     * @param playerUuid the player's UUID
     * @throws SQLException on database error
     */
    public void adminClearHoldings(UUID playerUuid) throws SQLException {
        holdingRepo.deleteAll(playerUuid);
    }

    /**
     * Adds shares to a player's holding using weighted-average cost calculation,
     * without deducting any money.
     *
     * @param playerUuid the player's UUID
     * @param stockCode  case-insensitive stock code
     * @param amount     number of shares to add (must be > 0)
     * @param price      cost per share used for weighted-average calculation
     * @throws SQLException           on database error
     * @throws IllegalArgumentException if amount <= 0
     */
    public void adminAddHolding(UUID playerUuid, String stockCode,
                                int amount, double price) throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
        holdingRepo.adminAdd(playerUuid, stockCode.toUpperCase(), amount, price);
    }

    /**
     * Reduces a player's holding by the given amount without crediting any money.
     * If the resulting amount would be <= 0 the holding is removed entirely.
     * Does nothing if the player has no holding for this code.
     *
     * @param playerUuid   the player's UUID
     * @param stockCode    case-insensitive stock code
     * @param reduceAmount number of shares to remove (must be > 0)
     * @throws SQLException           on database error
     * @throws IllegalArgumentException if reduceAmount <= 0
     */
    public void adminReduceHolding(UUID playerUuid, String stockCode,
                                   int reduceAmount) throws SQLException {
        if (reduceAmount <= 0) throw new IllegalArgumentException("reduceAmount must be > 0");
        holdingRepo.adminReduce(playerUuid, stockCode.toUpperCase(), reduceAmount);
    }

    // ── Session utilities ─────────────────────────────────────────────────────

    /**
     * Returns whether the player currently has an active buy/sell input session
     * (i.e. they are in the middle of the interactive trade flow).
     *
     * @param playerUuid the player's UUID
     * @return {@code true} if a live session exists
     */
    public boolean hasActiveSession(UUID playerUuid) {
        return chatSession.hasSession(playerUuid);
    }

    /**
     * Forcibly clears any active trade session for the player.
     * Safe to call even if no session exists.
     *
     * @param playerUuid the player's UUID
     */
    public void clearSession(UUID playerUuid) {
        chatSession.clearSession(playerUuid);
    }

    // ── Plugin reference ──────────────────────────────────────────────────────

    /**
     * Returns the underlying {@link Plugin} instance. Useful for scheduling tasks
     * relative to the MineStock plugin lifecycle.
     */
    public Plugin getPlugin() {
        return plugin;
    }
}
