package dev.onelili.mstock.command;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.api.event.StockBuyEvent;
import dev.onelili.mstock.api.event.StockPortfolioQueryEvent;
import dev.onelili.mstock.api.event.StockPreBuyEvent;
import dev.onelili.mstock.api.event.StockPreSellEvent;
import dev.onelili.mstock.api.event.StockQueryEvent;
import dev.onelili.mstock.api.event.StockSellEvent;
import dev.onelili.mstock.config.MainConfig;
import dev.onelili.mstock.database.HoldingRepository;
import dev.onelili.mstock.economy.EconomyService;
import dev.onelili.mstock.model.Holding;
import dev.onelili.mstock.model.KLinePoint;
import dev.onelili.mstock.model.StockInfo;
import dev.onelili.mstock.stockio.StockApiService;
import dev.onelili.mstock.ui.ChatInputSession;
import dev.onelili.mstock.ui.PendingAction;
import dev.onelili.mstock.util.KLineRenderer;
import dev.onelili.mstock.util.LangUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class MStockCommand implements CommandExecutor, TabCompleter {
    private final MineStock plugin;
    private final StockApiService api;
    private final HoldingRepository holdingRepo;
    private final EconomyService economy;
    private final MainConfig config;
    private final LangUtil lang;
    private final ChatInputSession session;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final DecimalFormat df = new DecimalFormat("#0.00");

    public MStockCommand(MineStock plugin) {
        this.plugin = plugin;
        this.api = plugin.getApi();
        this.holdingRepo = plugin.getHoldingRepo();
        this.economy = plugin.getEconomy();
        this.config = plugin.getMainConfig();
        this.lang = plugin.getLang();
        this.session = plugin.getChatSession();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此命令只能由玩家执行");
            return true;
        }

        if (!economy.isReady()) {
            lang.send(player, "vault-not-found");
            return true;
        }

        if (args.length == 0) {
            lang.sendNoPrefix(player, "usage");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "buy" -> {
                if (args.length == 3) {
                    try {
                        executeBuy(player, args[1].toUpperCase(), Integer.parseInt(args[2]));
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                    }
                } else {
                    lang.sendNoPrefix(player, "usage");
                }
            }
            case "sell" -> {
                if (args.length == 3) {
                    try {
                        executeSell(player, args[1].toUpperCase(), Integer.parseInt(args[2]));
                    } catch (NumberFormatException e) {
                        lang.send(player, "input-invalid");
                    }
                } else {
                    lang.sendNoPrefix(player, "usage");
                }
            }
            case "recommended", "rec", "r" -> showRecommendations(player);
            case "portfolio", "p" -> showPortfolio(player);
            case "reload" -> {
                if (!player.hasPermission("minestock.admin")) {
                    lang.send(player, "reload-no-permission");
                    return true;
                }
                plugin.reload();
                lang.send(player, "reload-success");
            }
            case "_buy_init" -> {
                if (args.length == 2) {
                    initBuy(player, args[1].toUpperCase());
                }
            }
            case "_sell_init" -> {
                if (args.length == 2) {
                    initSell(player, args[1].toUpperCase());
                }
            }
            case "_cancel_confirm" -> {
                PendingAction pending = session.getSession(player.getUniqueId());
                if (pending != null && pending.isAwaitingConfirm()) {
                    session.clearSession(player.getUniqueId());
                    lang.send(player, "input-cancelled");
                }
            }
            case "_buy_confirm" -> {
                // /mstock _buy_confirm <code> <amount>
                if (args.length == 3) {
                    try {
                        int amount = Integer.parseInt(args[2]);
                        // Only honour if there is a matching session awaiting confirm
                        PendingAction action = session.getSession(player.getUniqueId());
                        if (action != null && action.getType() == PendingAction.Type.BUY
                                && action.getStockCode().equals(args[1].toUpperCase())
                                && action.isAwaitingConfirm()) {
                            session.clearSession(player.getUniqueId());
                            executeBuy(player, args[1].toUpperCase(), amount);
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
            case "_sell_confirm" -> {
                // /mstock _sell_confirm <code> <amount>
                if (args.length == 3) {
                    try {
                        int amount = Integer.parseInt(args[2]);
                        PendingAction action = session.getSession(player.getUniqueId());
                        if (action != null && action.getType() == PendingAction.Type.SELL
                                && action.getStockCode().equals(args[1].toUpperCase())
                                && action.isAwaitingConfirm()) {
                            session.clearSession(player.getUniqueId());
                            executeSell(player, args[1].toUpperCase(), amount);
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
            case "_kline" -> {
                // _kline <code> <days>
                if (args.length == 3) {
                    try {
                        int days = Integer.parseInt(args[2]);
                        showKLine(player, args[1].toUpperCase(), days);
                    } catch (NumberFormatException ignored) { }
                }
            }

            default -> {
                String code = args[0].toUpperCase();
                if (api.isSupported(code)) {
                    showStockDetail(player, code);
                } else {
                    lang.send(player, "unsupported-code", "code", code);
                }
            }
        }
        return true;
    }

    private void showRecommendations(Player player) {
        if (checkCooldown(player)) return;

        List<String> pool = config.getRecommendedPool();
        if (pool.isEmpty()) {
            player.sendMessage("管理员未配置推荐股票池");
            return;
        }

        lang.sendNoPrefix(player, "recommend-header");
        player.sendMessage(LangUtil.parse("  <gray>正在获取行情…</gray>"));

        List<CompletableFuture<StockInfo>> futures = pool.stream()
                .map(api::fetch)
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    List<StockInfo> stocks = futures.stream()
                            .map(f -> f.getNow(null))
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparingDouble(StockInfo::getChangePercent).reversed())
                            .limit(config.getRecommendedCount())
                            .toList();

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        for (StockInfo info : stocks) {
                            player.sendMessage(buildRecommendLine(info));
                        }
                        lang.sendNoPrefix(player, "recommend-hint");
                    });
                });
    }

    private Component buildRecommendLine(StockInfo info) {
        String key = info.getChangePercent() >= 0 ? "recommend-item-up" : "recommend-item-down";
        return lang.getNoPrefix(key,
                "code", info.getCode(),
                "name", info.getName(),
                "price", df.format(info.getPrice() * config.getPriceRatio()),
                "change", df.format(Math.abs(info.getChangePercent()))
        );
    }

    private void showStockDetail(Player player, String code) {
        if (checkCooldown(player)) return;

        StockQueryEvent queryEvent = new StockQueryEvent(player, code);
        plugin.getServer().getPluginManager().callEvent(queryEvent);
        if (queryEvent.isCancelled()) return;

        lang.send(player, "api-fetching", "code", code);

        // Fetch real-time price and default 30-day K-line in parallel, render together
        CompletableFuture<StockInfo> priceFuture = api.fetch(code);
        CompletableFuture<List<KLinePoint>> klineFuture = api.fetchKLine(code, 30);

        priceFuture.thenCombine(klineFuture, (info, points) -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    renderFullDetail(player, code, info, points, 30));
            return null;
        }).exceptionally(ex -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    lang.send(player, "api-error"));
            return null;
        });
    }

    private void showPortfolio(Player player) {
        StockPortfolioQueryEvent portfolioEvent = new StockPortfolioQueryEvent(player);
        plugin.getServer().getPluginManager().callEvent(portfolioEvent);
        if (portfolioEvent.isCancelled()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<Holding> holdings = holdingRepo.findAllByPlayer(player.getUniqueId());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    lang.sendNoPrefix(player, "portfolio-header");
                    if (holdings.isEmpty()) {
                        lang.sendNoPrefix(player, "portfolio-empty");
                        return;
                    }
                    for (Holding h : holdings) {
                        api.fetch(h.getStockCode()).thenAccept(info ->
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    double avgCost = h.getAvgCost() * config.getPriceRatio();
                                    double currentPrice = info.getPrice() * config.getPriceRatio();
                                    double changePct = avgCost > 0
                                            ? (currentPrice - avgCost) / avgCost * 100.0
                                            : 0.0;
                                    String key = changePct >= 0 ? "portfolio-item-up" : "portfolio-item-down";
                                    player.sendMessage(lang.getNoPrefix(key,
                                            "code", h.getStockCode(),
                                            "name", info.getName(),
                                            "amount", String.valueOf(h.getAmount()),
                                            "avg_cost", df.format(avgCost),
                                            "price", df.format(currentPrice),
                                            "change", df.format(Math.abs(changePct))
                                    ));
                                })
                        ).exceptionally(ex -> null);
                    }
                });
            } catch (SQLException e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        lang.send(player, "db-error"));
            }
        });
    }

    /** Called by _kline command. Checks cooldown then fetches+renders the full detail page. */
    private void showKLine(Player player, String code, int days) {
        if (days < 1 || days > 365) return;
        if (checkCooldown(player)) return;
        if (!api.isSupported(code)) {
            lang.send(player, "unsupported-code", "code", code);
            return;
        }
        lang.send(player, "api-fetching", "code", code);

        CompletableFuture<StockInfo> priceFuture = api.fetch(code);
        CompletableFuture<List<KLinePoint>> klineFuture = api.fetchKLine(code, days);

        priceFuture.thenCombine(klineFuture, (info, points) -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    renderFullDetail(player, code, info, points, days));
            return null;
        }).exceptionally(ex -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    lang.send(player, "api-error"));
            return null;
        });
    }

    /**
     * Renders the complete stock detail page in one shot. Order:
     *   1. stock-header (name + code)
     *   2. stock-price (price + change%)
     *   3. K-line chart rows
     *   4. current period label + period selector buttons
     *   5. buy / sell action buttons
     * Must be called on the main thread.
     */
    private void renderFullDetail(Player player, String code, StockInfo info,
                                  List<KLinePoint> points, int days) {
        // 1. Header + price on one line
        String headerKey = info.getChangePercent() >= 0 ? "stock-header-up" : "stock-header-down";
        lang.sendNoPrefix(player, headerKey,
                "name",   info.getName(),
                "code",   info.getCode(),
                "price",  df.format(info.getPrice() * config.getPriceRatio()),
                "change", df.format(Math.abs(info.getChangePercent())));

        // 2. K-line chart
        List<String> chartLines = KLineRenderer.render(points);
        for (String line : chartLines) {
            player.sendMessage(LangUtil.parse(line));
        }

        // 3. Period label + selector
        lang.sendNoPrefix(player, "kline-current-period", "days", String.valueOf(days));
        player.sendMessage(LangUtil.parse(buildPeriodBar(code, days)));

        // 4. Buy / sell
        lang.sendNoPrefix(player, "stock-actions", "code", code);
    }

    /**
     * Builds the period selector bar as a MiniMessage string.
     * Selected period is green (no click), others are gray with click+hover.
     */
    private String buildPeriodBar(String code, int selectedDays) {
        int[] presetDays = {30, 90, 365};
        String[] presetLabels = {"1月", "3月", "1年"};

        StringBuilder sb = new StringBuilder("  ");
        for (int i = 0; i < presetDays.length; i++) {
            int d = presetDays[i];
            String label = presetLabels[i];
            if (d == selectedDays) {
                sb.append("<green>[ ").append(label).append(" ]</green>");
            } else {
                sb.append("<gray><click:run_command:'/mstock _kline ").append(code).append(" ").append(d).append("'>")
                  .append("<hover:show_text:'<gray>查看 ").append(label).append(" K线</gray>'>")
                  .append("[ ").append(label).append(" ]")
                  .append("</hover></click></gray>");
            }
            sb.append("  ");
        }
        return sb.toString();
    }

    /** Starts a buy session: fetch the current price first, then send the prompt with price info. */
    private void initBuy(Player player, String code) {
        lang.send(player, "api-fetching", "code", code);
        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                session.startSession(player.getUniqueId(), new PendingAction(PendingAction.Type.BUY, code));
                lang.send(player, "buy-prompt",
                        "code", code,
                        "price", df.format(unitPrice));
            });
        }).exceptionally(ex -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    lang.send(player, "api-error"));
            return null;
        });
    }

    /** Starts a sell session: fetch the current price first, then send the prompt with price info. */
    private void initSell(Player player, String code) {
        lang.send(player, "api-fetching", "code", code);
        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                session.startSession(player.getUniqueId(), new PendingAction(PendingAction.Type.SELL, code));
                lang.send(player, "sell-prompt",
                        "code", code,
                        "price", df.format(unitPrice));
            });
        }).exceptionally(ex -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    lang.send(player, "api-error"));
            return null;
        });
    }

    /**
     * Called from ChatInputListener after the player types an amount.
     * Fetches the latest price, shows the trade preview with confirm/cancel buttons,
     * and keeps the session alive waiting for the player to click confirm.
     * Must be called on the main thread.
     */
    public void showTradeConfirm(Player player, PendingAction action) {
        String code = action.getStockCode();
        int amount = action.getAmount();
        boolean isBuy = action.getType() == PendingAction.Type.BUY;

        lang.send(player, "api-fetching", "code", code);

        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            double subtotal  = round2(unitPrice * amount);
            double fee       = calcFee(subtotal);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (isBuy) {
                    double total = round2(subtotal + fee);
                    lang.sendNoPrefix(player, "buy-confirm",
                            "unit_price", df.format(unitPrice),
                            "amount",     String.valueOf(amount),
                            "subtotal",   df.format(subtotal),
                            "fee",        df.format(fee),
                            "total",      df.format(total));
                } else {
                    double income = round2(subtotal - fee);
                    lang.sendNoPrefix(player, "sell-confirm",
                            "unit_price", df.format(unitPrice),
                            "amount",     String.valueOf(amount),
                            "subtotal",   df.format(subtotal),
                            "fee",        df.format(fee),
                            "income",     df.format(income));
                }

                // Confirm / cancel buttons
                String confirmCmd = isBuy
                        ? "/mstock _buy_confirm " + code + " " + amount
                        : "/mstock _sell_confirm " + code + " " + amount;
                String confirmLine =
                        "<click:run_command:'/mstock _cancel_confirm'>" +
                        "<hover:show_text:'<gray>取消本次交易</gray>'>" +
                        "<red>[ 取消 ]</red></hover></click>" +
                        "   " +
                        "<click:run_command:'" + confirmCmd + "'>" +
                        "<hover:show_text:'<gray>确认并执行交易</gray>'>" +
                        "<green>[ 确认 ]</green></hover></click>";
                player.sendMessage(LangUtil.parse("  " + confirmLine));
            });
        }).exceptionally(ex -> {
            session.clearSession(player.getUniqueId());
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    lang.send(player, "api-error"));
            return null;
        });
    }

    public void executeBuy(Player player, String code, int amount) {
        if (amount <= 0) {
            lang.send(player, "input-invalid");
            return;
        }

        if (!api.isSupported(code)) {
            lang.send(player, "unsupported-code", "code", code);
            return;
        }

        api.fetch(code).thenAccept(info -> {
            double unitPrice = round2(info.getPrice() * config.getPriceRatio());
            double subtotal  = round2(unitPrice * amount);
            double fee       = calcFee(subtotal);
            double total     = round2(subtotal + fee);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!economy.has(player, total)) {
                    lang.send(player, "not-enough-money",
                            "need",    df.format(total),
                            "balance", df.format(economy.getBalance(player)));
                    return;
                }

                StockPreBuyEvent preEvent = new StockPreBuyEvent(player, code, amount, unitPrice, fee, total);
                plugin.getServer().getPluginManager().callEvent(preEvent);
                if (preEvent.isCancelled()) return;

                if (!economy.withdraw(player, total)) {
                    lang.send(player, "db-error");
                    return;
                }

                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        holdingRepo.upsertBuy(player.getUniqueId(), code, amount, unitPrice);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            plugin.getServer().getPluginManager().callEvent(
                                    new StockBuyEvent(player, code, amount, unitPrice, fee, total));
                            lang.send(player, "buy-success",
                                    "code",   code,
                                    "amount", String.valueOf(amount),
                                    "total",  df.format(total),
                                    "fee",    df.format(fee));
                        });
                    } catch (SQLException e) {
                        plugin.getLogger().warning("买入写库失败: " + e.getMessage());
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            economy.deposit(player, total);
                            lang.send(player, "db-error");
                        });
                    }
                });
            });
        }).exceptionally(ex -> {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    lang.send(player, "api-error"));
            return null;
        });
    }

    public void executeSell(Player player, String code, int amount) {
        if (amount <= 0) {
            lang.send(player, "input-invalid");
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Holding holding = holdingRepo.findByPlayerAndCode(player.getUniqueId(), code);
                if (holding == null) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            lang.send(player, "no-holding", "code", code));
                    return;
                }

                if (holding.getAmount() < amount) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            lang.send(player, "not-enough-holding",
                                    "hold",   String.valueOf(holding.getAmount()),
                                    "amount", String.valueOf(amount)));
                    return;
                }

                api.fetch(code).thenAccept(info -> {
                    double sellPrice = round2(info.getPrice() * config.getPriceRatio());
                    double subtotal  = round2(sellPrice * amount);
                    double fee       = calcFee(subtotal);
                    double income    = round2(subtotal - fee);

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        StockPreSellEvent preEvent = new StockPreSellEvent(player, code, amount, sellPrice, fee, income);
                        plugin.getServer().getPluginManager().callEvent(preEvent);
                        if (preEvent.isCancelled()) return;

                        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                            try {
                                holdingRepo.reduceSell(player.getUniqueId(), code, amount, sellPrice);
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if (!economy.deposit(player, income)) {
                                        plugin.getLogger().severe("卖出加钱失败！玩家 " + player.getName());
                                    }
                                    plugin.getServer().getPluginManager().callEvent(
                                            new StockSellEvent(player, code, amount, sellPrice, fee, income));
                                    lang.send(player, "sell-success",
                                            "code",   code,
                                            "amount", String.valueOf(amount),
                                            "income", df.format(income),
                                            "fee",    df.format(fee));
                                });
                            } catch (SQLException e) {
                                plugin.getLogger().warning("卖出写库失败: " + e.getMessage());
                                plugin.getServer().getScheduler().runTask(plugin, () ->
                                        lang.send(player, "db-error"));
                            }
                        });
                    });
                }).exceptionally(ex -> {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            lang.send(player, "api-error"));
                    return null;
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("查询持仓失败: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        lang.send(player, "db-error"));
            }
        });
    }

    /** Rounds a value to 2 decimal places (HALF_UP) to avoid floating-point drift. */
    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Calculates transaction fee for the given subtotal, rounded to 2 decimal places. */
    private double calcFee(double subtotal) {
        double rate = config.getTransactionFeeRate();
        if (rate <= 0) return 0.0;
        return BigDecimal.valueOf(subtotal)
                .multiply(BigDecimal.valueOf(rate))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private boolean checkCooldown(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        long cdMs = config.getApiCooldownMs();
        if (last != null && now - last < cdMs) {
            long remainMs = cdMs - (now - last);
            String display = remainMs >= 1000
                    ? (remainMs / 1000) + "." + (remainMs % 1000 / 100) + " 秒"
                    : remainMs + " ms";
            lang.send(player, "api-cooldown", "seconds", display);
            return true;
        }
        cooldowns.put(uuid, now);
        return false;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("buy", "sell", "portfolio", "recommended", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
