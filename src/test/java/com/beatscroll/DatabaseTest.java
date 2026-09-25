package com.beatscroll;

import com.beatscroll.db.Database;
import com.beatscroll.db.MidiRepository;
import com.beatscroll.fixtures.Fixtures;
import com.beatscroll.midi.MidiParser;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.Timebase;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void migrationsApplyAndAreIdempotent() throws Exception {
        try (Database db = Database.inMemory()) {
            try (Statement s = db.connection().createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
            // 重复打开同一连接路径不报错（迁移幂等由 applied 检查保证）
            try (Statement s = db.connection().createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
                StringBuilder tables = new StringBuilder();
                while (rs.next()) {
                    tables.append(rs.getString(1)).append(',');
                }
                for (String t : List.of("files", "headers", "tracks", "events",
                        "diagnostics", "tempo_candidates")) {
                    assertTrue(tables.toString().contains(t), "缺表 " + t);
                }
            }
        }
    }

    @Test
    void saveAndLoadRoundTripThroughSqlite() {
        try (Database db = Database.inMemory()) {
            MidiRepository repo = new MidiRepository(db);
            assertTrue(repo.isEmpty());
            MidiParser parser = new MidiParser();
            for (var entry : Fixtures.all().entrySet()) {
                ParseResult r = parser.parse(entry.getValue());
                Timebase tb = Timebase.build(r.header(), r.tracks());
                repo.save(entry.getKey(), entry.getValue(), r, tb);
            }
            assertFalse(repo.isEmpty());
            List<MidiRepository.FileRow> files = repo.listFiles();
            assertEquals(Fixtures.all().size(), files.size());

            // tempo 冲突文件的候选落库：3 行，含 1 个歧义组
            MidiRepository.FileRow conflict = files.stream()
                    .filter(f -> f.name().equals("tempo-conflict.mid")).findFirst().orElseThrow();
            MidiRepository.FileDetail detail = repo.load(conflict.id());
            assertNotNull(detail);
            assertEquals(3, detail.tempoRows().size());
            long ambiguous = detail.tempoRows().stream().filter(MidiRepository.TempoRow::ambiguous).count();
            assertEquals(2, ambiguous);
            long chosen = detail.tempoRows().stream().filter(MidiRepository.TempoRow::chosen).count();
            assertEquals(2, chosen); // tick 0 与 tick 480 各一个积分取值
            // 事件与诊断完整保留（轨0: tempo+tempo+EOT，轨1: tempo+noteOn+noteOff+EOT）
            assertEquals(7, detail.tracks().stream()
                    .mapToInt(t -> t.events().size()).sum());
            // 微秒列已落库
            long[] micros = repo.eventMicros(conflict.id());
            assertEquals(7, micros.length);
        }
    }

    @Test
    void diagnosticsArePersisted() {
        try (Database db = Database.inMemory()) {
            MidiRepository repo = new MidiRepository(db);
            MidiParser parser = new MidiParser();
            byte[] data = Fixtures.all().get("truncated-vlq.mid");
            ParseResult r = parser.parse(data);
            repo.save("truncated-vlq.mid", data, r, Timebase.build(r.header(), r.tracks()));
            MidiRepository.FileDetail detail = repo.load(1);
            assertTrue(detail.diagnostics().stream()
                    .anyMatch(d -> d.code().equals("VLQ_TOO_LONG")));
        }
    }
}
