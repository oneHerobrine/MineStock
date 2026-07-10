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
        Holding holding = findByPlayerAndCode(playerUuid, code);
        if (holding == null) throw new SQLException("Holding not found");
        int remaining = holding.getAmount() - sellAmount;
        long now = System.currentTimeMillis();
        if (remaining <= 0) {
            delete(playerUuid, code);
        } else {
            String sql = "UPDATE holdings SET amount = ?, last_price = ?, last_fetched = ? WHERE player_uuid = ? AND stock_code = ?";
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, remaining);
                ps.setDouble(2, sellPrice);
                ps.setLong(3, now);
                ps.setString(4, playerUuid.toString());
                ps.setString(5, code);
                ps.executeUpdate();
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
