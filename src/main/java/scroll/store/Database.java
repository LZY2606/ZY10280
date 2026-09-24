package scroll.store;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import scroll.midi.Diagnostic;
import scroll.midi.Division;
import scroll.midi.MidiEvent;
import scroll.midi.MidiFile;
import scroll.midi.MidiTrack;
import scroll.midi.TempoMap;

/** SQLite 持久化：schema_migrations 驱动的迁移 + 解析结果存取。 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(Path path) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            conn.setAutoCommit(true);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法打开 SQLite: " + path, e);
        }
    }

    public void migrate() {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations(
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
                )""");
            if (currentVersion() < 1) {
                s.executeUpdate("""
                    CREATE TABLE files(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        format INTEGER NOT NULL,
                        ntracks INTEGER NOT NULL,
                        division_raw INTEGER NOT NULL,
                        smpte INTEGER NOT NULL,
                        ppqn INTEGER NOT NULL,
                        fps INTEGER NOT NULL,
                        ticks_per_frame INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        imported_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )""");
                s.executeUpdate("""
                    CREATE TABLE tracks(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                        track_index INTEGER NOT NULL,
                        chunk_offset INTEGER NOT NULL,
                        declared_length INTEGER NOT NULL
                    )""");
                s.executeUpdate("""
                    CREATE TABLE events(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                        track_index INTEGER NOT NULL,
                        seq INTEGER NOT NULL,
                        abs_tick INTEGER NOT NULL,
                        delta INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        status INTEGER NOT NULL,
                        effective_status INTEGER NOT NULL,
                        running INTEGER NOT NULL,
                        channel INTEGER NOT NULL,
                        command INTEGER NOT NULL,
                        meta_type INTEGER NOT NULL,
                        data_hex TEXT NOT NULL,
                        delta_offset INTEGER NOT NULL,
                        event_offset INTEGER NOT NULL,
                        event_length INTEGER NOT NULL,
                        micros INTEGER NOT NULL
                    )""");
                s.executeUpdate("""
                    CREATE TABLE tempo_candidates(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                        track_index INTEGER NOT NULL,
                        tick INTEGER NOT NULL,
                        us_per_quarter INTEGER NOT NULL,
                        file_order INTEGER NOT NULL,
                        effective INTEGER NOT NULL
                    )""");
                s.executeUpdate("""
                    CREATE TABLE diagnostics(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                        track_index INTEGER NOT NULL,
                        code TEXT NOT NULL,
                        message TEXT NOT NULL,
                        offset INTEGER NOT NULL
                    )""");
                s.executeUpdate("INSERT INTO schema_migrations(version) VALUES (1)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("迁移失败", e);
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 导入解析结果；同名同哈希则复用旧记录。返回 file id。 */
    public long importFile(MidiFile file) {
        String sha = sha256(file.source);
        try {
            Long existing = queryLong(
                    "SELECT id FROM files WHERE name=? AND sha256=?", file.name, sha);
            if (existing != null) return existing;

            conn.setAutoCommit(false);
            try {
                long fileId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO files(name,format,ntracks,division_raw,smpte,ppqn,fps,ticks_per_frame,sha256)
                        VALUES (?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
                    Division d = file.division;
                    ps.setString(1, file.name);
                    ps.setInt(2, file.format);
                    ps.setInt(3, file.ntracks);
                    ps.setInt(4, d.raw());
                    ps.setInt(5, d.smpte() ? 1 : 0);
                    ps.setInt(6, d.ppqn());
                    ps.setInt(7, d.fps());
                    ps.setInt(8, d.ticksPerFrame());
                    ps.setString(9, sha);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        fileId = keys.getLong(1);
                    }
                }

                // 计算每事件微秒：SMPTE 直接换算；format 2 每轨独立 tempo map；否则全局 map
                TempoMap global = file.division.smpte() ? null
                        : (file.format == 2 ? null : TempoMap.build(file));
                TempoMap[] perTrack = new TempoMap[file.tracks.size()];
                if (!file.division.smpte() && file.format == 2) {
                    for (MidiTrack t : file.tracks) {
                        perTrack[t.index] = TempoMap.buildFor(file, t.index);
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tracks(file_id,track_index,chunk_offset,declared_length) VALUES (?,?,?,?)")) {
                    for (MidiTrack t : file.tracks) {
                        ps.setLong(1, fileId);
                        ps.setInt(2, t.index);
                        ps.setInt(3, t.chunkOffset);
                        ps.setInt(4, t.declaredLength);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO events(file_id,track_index,seq,abs_tick,delta,kind,status,effective_status,
                            running,channel,command,meta_type,data_hex,delta_offset,event_offset,event_length,micros)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                    for (MidiTrack t : file.tracks) {
                        for (MidiEvent ev : t.events) {
                            long micros;
                            if (file.division.smpte()) {
                                micros = file.division.tickToMicros(ev.absTick);
                            } else if (file.format == 2) {
                                micros = perTrack[t.index].tickToMicros(ev.absTick);
                            } else {
                                micros = global.tickToMicros(ev.absTick);
                            }
                            ps.setLong(1, fileId);
                            ps.setInt(2, t.index);
                            ps.setInt(3, ev.seq);
                            ps.setLong(4, ev.absTick);
                            ps.setLong(5, ev.delta);
                            ps.setString(6, ev.kind.name());
                            ps.setInt(7, ev.status);
                            ps.setInt(8, ev.effectiveStatus);
                            ps.setInt(9, ev.runningStatus ? 1 : 0);
                            ps.setInt(10, ev.channel);
                            ps.setInt(11, ev.command);
                            ps.setInt(12, ev.metaType);
                            ps.setString(13, ev.dataHex());
                            ps.setInt(14, ev.deltaOffset);
                            ps.setInt(15, ev.eventOffset);
                            ps.setInt(16, ev.eventLength);
                            ps.setLong(17, micros);
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }

                List<TempoMap.Candidate> candidates = new ArrayList<>();
                if (global != null) candidates.addAll(global.candidates());
                for (TempoMap m : perTrack) if (m != null) candidates.addAll(m.candidates());
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO tempo_candidates(file_id,track_index,tick,us_per_quarter,file_order,effective)
                        VALUES (?,?,?,?,?,?)""")) {
                    for (TempoMap.Candidate cand : candidates) {
                        ps.setLong(1, fileId);
                        ps.setInt(2, cand.trackIndex());
                        ps.setLong(3, cand.tick());
                        ps.setInt(4, cand.usPerQuarter());
                        ps.setInt(5, cand.fileOrder());
                        ps.setInt(6, cand.effective() ? 1 : 0);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO diagnostics(file_id,track_index,code,message,offset) VALUES (?,?,?,?,?)")) {
                    for (Diagnostic d : file.diagnostics) {
                        ps.setLong(1, fileId);
                        ps.setInt(2, d.trackIndex());
                        ps.setString(3, d.code());
                        ps.setString(4, d.message());
                        ps.setInt(5, d.offset());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return fileId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("导入失败: " + file.name, e);
        }
    }

    public record FileRow(long id, String name, int format, int ntracks, int divisionRaw,
            boolean smpte, int ppqn, int fps, int ticksPerFrame, String importedAt) {}

    public List<FileRow> listFiles() {
        List<FileRow> rows = new ArrayList<>();
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT id,name,format,ntracks,division_raw,smpte,ppqn,fps,ticks_per_frame,imported_at"
                                + " FROM files ORDER BY id")) {
            while (rs.next()) {
                rows.add(new FileRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6) == 1, rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        rs.getString(10)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public FileRow file(long id) {
        return listFiles().stream().filter(f -> f.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("无此文件: " + id));
    }

    public record EventRow(int trackIndex, int seq, long absTick, long delta, String kind,
            int status, int effectiveStatus, boolean running, int channel, int command,
            int metaType, String dataHex, int deltaOffset, int eventOffset, int eventLength,
            long micros) {}

    public List<EventRow> events(long fileId) {
        List<EventRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT track_index,seq,abs_tick,delta,kind,status,effective_status,running,channel,
                       command,meta_type,data_hex,delta_offset,event_offset,event_length,micros
                FROM events WHERE file_id=? ORDER BY track_index, seq""")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new EventRow(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4),
                            rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8) == 1,
                            rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getString(12),
                            rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getLong(16)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public record TempoRow(int trackIndex, long tick, int usPerQuarter, int fileOrder,
            boolean effective) {}

    public List<TempoRow> tempoCandidates(long fileId) {
        List<TempoRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT track_index,tick,us_per_quarter,file_order,effective FROM tempo_candidates"
                        + " WHERE file_id=? ORDER BY tick, file_order")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TempoRow(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getInt(4),
                            rs.getInt(5) == 1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public record DiagRow(int trackIndex, String code, String message, int offset) {}

    public List<DiagRow> diagnostics(long fileId) {
        List<DiagRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT track_index,code,message,offset FROM diagnostics WHERE file_id=? ORDER BY id")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DiagRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private Long queryLong(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 关闭失败无需处理
        }
    }
}
