package com.beatscroll.db;

import com.beatscroll.midi.Diagnostic;
import com.beatscroll.midi.MidiEvent;
import com.beatscroll.midi.MidiHeader;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.TempoMap;
import com.beatscroll.midi.Timebase;
import com.beatscroll.midi.Timing;
import com.beatscroll.midi.TrackData;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 把解析结果持久化到 SQLite，并为页面提供查询。 */
public final class MidiRepository {

    public record FileRow(long id, String name, String sha256, String importedAt,
                          int format, String timing, int trackCount, int eventCount,
                          int errorCount, int warnCount) {
    }

    public record TempoRow(int scopeTrack, long tick, int tempoUs, int trackIndex,
                           long seq, boolean chosen, boolean ambiguous) {
    }

    public record FileDetail(long id, String name, MidiHeader header, List<TrackData> tracks,
                             List<Diagnostic> diagnostics, List<TempoRow> tempoRows) {
    }

    private final Connection conn;

    public MidiRepository(Database db) {
        this.conn = db.connection();
    }

    public boolean isEmpty() {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM files")) {
            return rs.next() && rs.getLong(1) == 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 保存解析结果；微秒列由 timebase 在导入时计算。 */
    public long save(String name, byte[] bytes, ParseResult result, Timebase timebase) {
        try {
            conn.setAutoCommit(false);
            long fileId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO files(name, data, sha256, imported_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setBytes(2, bytes);
                ps.setString(3, sha256(bytes));
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    fileId = keys.getLong(1);
                }
            }
            MidiHeader h = result.header();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO headers(file_id, format, ntrks, division_raw, timing_kind,"
                            + " ppqn, smpte_fps, smpte_tpf) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, fileId);
                ps.setInt(2, h.format());
                ps.setInt(3, h.ntrks());
                ps.setInt(4, h.divisionRaw());
                if (h.timing() instanceof Timing.Ppqn p) {
                    ps.setString(5, "PPQN");
                    ps.setInt(6, p.ticksPerQuarter());
                    ps.setNull(7, java.sql.Types.INTEGER);
                    ps.setNull(8, java.sql.Types.INTEGER);
                } else {
                    Timing.Smpte s = (Timing.Smpte) h.timing();
                    ps.setString(5, "SMPTE");
                    ps.setNull(6, java.sql.Types.INTEGER);
                    ps.setInt(7, s.fpsCode());
                    ps.setInt(8, s.ticksPerFrame());
                }
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tracks(file_id, track_index, chunk_offset, declared_length,"
                            + " actual_length) VALUES (?,?,?,?,?)")) {
                for (TrackData t : result.tracks()) {
                    ps.setLong(1, fileId);
                    ps.setInt(2, t.trackIndex());
                    ps.setInt(3, t.chunkOffset());
                    ps.setLong(4, t.declaredLength());
                    ps.setLong(5, t.actualLength());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO events(file_id, track_index, event_index, seq, delta_ticks,"
                            + " abs_tick, micros, raw_start, raw_end, delta_start, delta_end,"
                            + " kind, status, used_running_status, channel, data1, data2,"
                            + " meta_type, payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (TrackData t : result.tracks()) {
                    for (MidiEvent e : t.events()) {
                        ps.setLong(1, fileId);
                        ps.setInt(2, e.trackIndex());
                        ps.setInt(3, e.eventIndex());
                        ps.setLong(4, e.seq());
                        ps.setLong(5, e.deltaTicks());
                        ps.setLong(6, e.absTick());
                        ps.setLong(7, timebase.microsAt(e.trackIndex(), e.absTick()));
                        ps.setInt(8, e.rawStart());
                        ps.setInt(9, e.rawEnd());
                        ps.setInt(10, e.deltaStart());
                        ps.setInt(11, e.deltaEnd());
                        ps.setString(12, e.kind().name());
                        ps.setInt(13, e.status());
                        ps.setInt(14, e.usedRunningStatus() ? 1 : 0);
                        ps.setInt(15, e.channel());
                        ps.setInt(16, e.data1());
                        ps.setInt(17, e.data2());
                        ps.setInt(18, e.metaType());
                        ps.setBytes(19, e.payload());
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            List<Diagnostic> allDiags = new ArrayList<>(result.diagnostics());
            allDiags.addAll(timebase.diagnostics());
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO diagnostics(file_id, severity, code, track_index, offset, message)"
                            + " VALUES (?,?,?,?,?,?)")) {
                for (Diagnostic d : allDiags) {
                    ps.setLong(1, fileId);
                    ps.setString(2, d.severity().name());
                    ps.setString(3, d.code());
                    ps.setInt(4, d.trackIndex());
                    ps.setLong(5, d.offset());
                    ps.setString(6, d.message());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            saveTempoCandidates(fileId, result, timebase);
            conn.commit();
            conn.setAutoCommit(true);
            return fileId;
        } catch (SQLException e) {
            try {
                conn.rollback();
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 回滚失败无需上报
            }
            throw new IllegalStateException("保存失败: " + name, e);
        }
    }

    private void saveTempoCandidates(long fileId, ParseResult result, Timebase timebase)
            throws SQLException {
        if (result.header().timing() instanceof Timing.Smpte) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tempo_candidates(file_id, scope_track, tick, tempo_us, track_index,"
                        + " seq, chosen, ambiguous) VALUES (?,?,?,?,?,?,?,?)")) {
            if (result.header().format() == 2) {
                for (TrackData t : result.tracks()) {
                    insertPoints(ps, fileId, t.trackIndex(), timebase.tempoMapFor(t.trackIndex()));
                }
            } else {
                insertPoints(ps, fileId, -1, timebase.tempoMapFor(0));
            }
            ps.executeBatch();
        }
    }

    private void insertPoints(PreparedStatement ps, long fileId, int scopeTrack, TempoMap map)
            throws SQLException {
        for (TempoMap.Point point : map.points()) {
            boolean first = true;
            for (TempoMap.Candidate c : point.candidates()) {
                ps.setLong(1, fileId);
                ps.setInt(2, scopeTrack);
                ps.setLong(3, c.tick());
                ps.setInt(4, c.tempoUsPerQuarter());
                ps.setInt(5, c.trackIndex());
                ps.setLong(6, c.seq());
                ps.setInt(7, first ? 1 : 0);
                ps.setInt(8, point.ambiguous() ? 1 : 0);
                ps.addBatch();
                first = false;
            }
        }
    }

    public List<FileRow> listFiles() {
        String sql = """
                SELECT f.id, f.name, f.sha256, f.imported_at, h.format, h.timing_kind,
                       h.ppqn, h.smpte_fps, h.smpte_tpf,
                       (SELECT COUNT(*) FROM tracks t WHERE t.file_id = f.id),
                       (SELECT COUNT(*) FROM events e WHERE e.file_id = f.id),
                       (SELECT COUNT(*) FROM diagnostics d WHERE d.file_id = f.id
                           AND d.severity = 'ERROR'),
                       (SELECT COUNT(*) FROM diagnostics d WHERE d.file_id = f.id
                           AND d.severity = 'WARN')
                FROM files f JOIN headers h ON h.file_id = f.id
                ORDER BY f.id
                """;
        List<FileRow> rows = new ArrayList<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                String timing;
                if ("PPQN".equals(rs.getString(6))) {
                    timing = "PPQN " + rs.getInt(7);
                } else {
                    timing = "SMPTE " + rs.getInt(8) + "fps×" + rs.getInt(9) + "tpf";
                }
                rows.add(new FileRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), timing, rs.getInt(10), rs.getInt(11),
                        rs.getInt(12), rs.getInt(13)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public FileDetail load(long fileId) {
        try {
            String name;
            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM files WHERE id = ?")) {
                ps.setLong(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    name = rs.getString(1);
                }
            }
            MidiHeader header;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT format, ntrks, division_raw FROM headers WHERE file_id = ?")) {
                ps.setLong(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    header = MidiHeader.of(rs.getInt(1), rs.getInt(2), rs.getInt(3));
                }
            }
            List<TrackData> tracks = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT track_index, chunk_offset, declared_length, actual_length"
                            + " FROM tracks WHERE file_id = ? ORDER BY track_index")) {
                ps.setLong(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tracks.add(new TrackData(rs.getInt(1), rs.getInt(2), rs.getLong(3),
                                rs.getLong(4), new ArrayList<>()));
                    }
                }
            }
            List<Diagnostic> diags = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT severity, code, track_index, offset, message"
                            + " FROM diagnostics WHERE file_id = ? ORDER BY rowid")) {
                ps.setLong(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        diags.add(new Diagnostic(
                                Diagnostic.Severity.valueOf(rs.getString(1)), rs.getString(2),
                                rs.getInt(3), rs.getLong(4), rs.getString(5)));
                    }
                }
            }
            List<TempoRow> tempoRows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT scope_track, tick, tempo_us, track_index, seq, chosen, ambiguous"
                            + " FROM tempo_candidates WHERE file_id = ? ORDER BY scope_track, tick, seq")) {
                ps.setLong(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tempoRows.add(new TempoRow(rs.getInt(1), rs.getLong(2), rs.getInt(3),
                                rs.getInt(4), rs.getLong(5), rs.getInt(6) == 1, rs.getInt(7) == 1));
                    }
                }
            }
            // 事件本体；微秒已落库，由 eventMicros() 按相同顺序取出
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT track_index, event_index, seq, delta_ticks, abs_tick, micros,"
                            + " raw_start, raw_end, delta_start, delta_end, kind, status,"
                            + " used_running_status, channel, data1, data2, meta_type, payload"
                            + " FROM events WHERE file_id = ? ORDER BY track_index, event_index")) {
                ps.setLong(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MidiEvent e = new MidiEvent(
                                rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4),
                                rs.getLong(5), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                                rs.getInt(10), MidiEvent.Kind.valueOf(rs.getString(11)),
                                rs.getInt(12), rs.getInt(13) == 1, rs.getInt(14), rs.getInt(15),
                                rs.getInt(16), rs.getInt(17), rs.getBytes(18));
                        tracks.get(e.trackIndex()).events().add(e);
                    }
                }
            }
            return new FileDetail(fileId, name, header, tracks, diags, tempoRows);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 事件微秒查询：展示页按 (track,event) 对齐。 */
    public long[] eventMicros(long fileId) {
        List<Long> values = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT micros FROM events WHERE file_id = ? ORDER BY track_index, event_index")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
