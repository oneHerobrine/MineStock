package dev.onelili.mstock.database;

import dev.onelili.mstock.model.Holding;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class HoldingRepository {
    private final DatabaseManager db;
    private final Logger logger;

    public HoldingRepository(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public Holding findByPlayerAndCode(UUID playerUuid, String code) throws SQLException {
        String sql = "SELECT * FROM holdings WHERE player_uuid = ? AND stock_code = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public List<Holding> findAllByPlayer(UUID playerUuid) throws SQLException {
        String sql = "SELECT * FROM holdings WHERE player_uuid = ? ORDER BY stock_code";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<Holding> list = new ArrayList<>();
                while (rs.next()) list.add(mapRow(rs));
                return list;
            }
        }
    }

    public void upsertBuy(UUID playerUuid, String code, int addAmount, double buyPrice) throws SQLException {
        String sql = """
                INSERT INTO holdings (player_uuid, stock_code, amount, avg_cost, last_price, last_fetched)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    avg_cost = (avg_cost * amount + ? * ?) / (amount + ?),
                    amount = amount + ?,
                    last_price = ?,
                    last_fetched = ?
                """;
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, code);
            ps.setInt(3, addAmount);
            ps.setDouble(4, buyPrice);
            ps.setDouble(5, buyPrice);
            ps.setLong(6, now);
            // UPDATE params
            ps.setDouble(7, buyPrice);
            ps.setInt(8, addAmount);
            ps.setInt(9, addAmount);
            ps.setInt(10, addAmount);
            ps.setDouble(11, buyPrice);
            ps.setLong(12, now);
            ps.executeUpdate();
        }
    }

    public void reduceSell(UUID playerUuid, String code, int sellAmount, double sellPrice) throws SQLException {
        String updateSql = """
                UPDATE holdings
                SET amount = amount - ?, last_price = ?, last_fetched = ?
                WHERE player_uuid = ? AND stock_code = ? AND amount >= ?
                """;
        String deleteSql = "DELETE FROM holdings WHERE player_uuid = ? AND stock_code = ? AND amount = 0";
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement update = conn.prepareStatement(updateSql);
                 PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                update.setInt(1, sellAmount);
                update.setDouble(2, sellPrice);
                update.setLong(3, now);
                update.setString(4, playerUuid.toString());
                update.setString(5, code);
                update.setInt(6, sellAmount);
                if (update.executeUpdate() == 0) {
                    conn.rollback();
                    throw new SQLException("Holding not found or insufficient");
                }

                delete.setString(1, playerUuid.toString());
                delete.setString(2, code);
                delete.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                if (!conn.getAutoCommit()) conn.rollback();
                throw e;
            }
        }
    }

    private void delete(UUID playerUuid, String code) throws SQLException {
        String sql = "DELETE FROM holdings WHERE player_uuid = ? AND stock_code = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    // ── Admin operations (bypass economy and events) ─────────────────────────

    /**
     * Directly sets a player's holding to the given amount and avg cost.
     * If amount <= 0 the holding is deleted. Creates the row if it doesn't exist.
     */
    public void adminUpsert(UUID playerUuid, String code, int amount, double avgCost) throws SQLException {
        if (amount <= 0) {
            delete(playerUuid, code);
            return;
        }
        String sql = """
                INSERT INTO holdings (player_uuid, stock_code, amount, avg_cost, last_price, last_fetched)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    amount = ?,
                    avg_cost = ?,
                    last_price = ?,
                    last_fetched = ?
                """;
        long now = System.currentTimeMillis();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, code);
            ps.setInt(3, amount);
            ps.setDouble(4, avgCost);
            ps.setDouble(5, avgCost);
            ps.setLong(6, now);
            ps.setInt(7, amount);
            ps.setDouble(8, avgCost);
            ps.setDouble(9, avgCost);
            ps.setLong(10, now);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes all holdings for the given player.
     */
    public void deleteAll(UUID playerUuid) throws SQLException {
        String sql = "DELETE FROM holdings WHERE player_uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Adds shares using weighted-average cost without touching the economy.
     * Reuses the same upsert logic as a regular buy.
     */
    public void adminAdd(UUID playerUuid, String code, int addAmount, double price) throws SQLException {
        upsertBuy(playerUuid, code, addAmount, price);
    }

    /**
     * Reduces a holding by {@code reduceAmount}. If the result would be <= 0 the row is deleted.
     * Does nothing if the player has no holding for this code.
     */
    public void adminReduce(UUID playerUuid, String code, int reduceAmount) throws SQLException {
        Holding holding = findByPlayerAndCode(playerUuid, code);
        if (holding == null) return;
        int remaining = holding.getAmount() - reduceAmount;
        if (remaining <= 0) {
            delete(playerUuid, code);
        } else {
            String sql = "UPDATE holdings SET amount = ?, last_fetched = ? WHERE player_uuid = ? AND stock_code = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, remaining);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, playerUuid.toString());
                ps.setString(4, code);
                ps.executeUpdate();
            }
        }
    }

    private Holding mapRow(ResultSet rs) throws SQLException {
        return new Holding(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("stock_code"),
                rs.getInt("amount"),
                rs.getDouble("avg_cost"),
                rs.getDouble("last_price"),
                rs.getLong("last_fetched")
        );
    }
}
