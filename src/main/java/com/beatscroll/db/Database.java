package com.beatscroll.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/** SQLite 连接与迁移执行器。 */
public final class Database implements AutoCloseable {

    private static final List<String> MIGRATIONS = List.of(
            "V1__init.sql",
            "V2__tempo_candidates.sql"
    );

    private final Connection conn;

    private Database(Connection conn) {
        this.conn = conn;
    }

    public static Database open(Path path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("无法打开 SQLite: " + path, e);
        }
    }

    /** 内存库，供测试使用。 */
    public static Database inMemory() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Database db = new Database(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                    + "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
        }
        for (int i = 0; i < MIGRATIONS.size(); i++) {
            int version = i + 1;
            if (applied(version)) {
                continue;
            }
            String sql = loadResource("/db/migration/" + MIGRATIONS.get(i));
            try (Statement s = conn.createStatement()) {
                for (String stmt : sql.split(";")) {
                    String trimmed = stmt.trim();
                    if (!trimmed.isEmpty()) {
                        s.execute(trimmed);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, version);
                ps.setString(2, Instant.now().toString());
                ps.executeUpdate();
            }
        }
    }

    private boolean applied(int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String loadResource(String name) {
        try (InputStream in = Database.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("缺少迁移资源 " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取迁移资源失败 " + name, e);
        }
    }

    public Connection connection() {
        return conn;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 关闭失败无需上报
        }
    }
}
