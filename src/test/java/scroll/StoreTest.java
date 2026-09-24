package scroll;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scroll.fixtures.Fixtures;
import scroll.midi.MidiFile;
import scroll.midi.MidiParser;
import scroll.store.Database;

class StoreTest {

    @TempDir
    Path dir;

    @Test
    void migrationsApplyAndAreIdempotent() {
        Database db = new Database(dir.resolve("t.db"));
        db.migrate();
        db.migrate(); // 第二次不应报错
        db.close();
    }

    @Test
    void importPersistsEventsTemposAndDiagnostics() {
        Database db = new Database(dir.resolve("t.db"));
        db.migrate();
        MidiFile f = MidiParser.parse(Fixtures.all().get("tempo-same-tick"), "tempo-same-tick");
        long id = db.importFile(f);

        List<Database.EventRow> events = db.events(id);
        assertEquals(f.allEvents().size(), events.size());
        // 微秒列已按 tempo map 计算（生效候选 500000）
        Database.EventRow noteOff = events.stream()
                .filter(e -> e.absTick() == 240 && e.kind().equals("CHANNEL")).findFirst().orElseThrow();
        assertEquals(240L * 500_000 / 480, noteOff.micros());

        List<Database.TempoRow> tempos = db.tempoCandidates(id);
        assertEquals(2, tempos.size());
        assertTrue(tempos.get(0).effective());
        assertFalse(tempos.get(1).effective());
        assertEquals(250_000, tempos.get(1).usPerQuarter());

        assertTrue(db.diagnostics(id).stream().anyMatch(d -> d.code().equals("TEMPO_AMBIGUITY")));

        // 幂等：同名同哈希复用记录
        assertEquals(id, db.importFile(MidiParser.parse(
                Fixtures.all().get("tempo-same-tick"), "tempo-same-tick")));
        db.close();
    }

    @Test
    void format2MicrosUsePerTrackTimelines() {
        Database db = new Database(dir.resolve("t.db"));
        db.migrate();
        long id = db.importFile(MidiParser.parse(
                Fixtures.all().get("format2-independent"), "format2-independent"));
        List<Database.EventRow> events = db.events(id);
        long t0 = events.stream().filter(e -> e.trackIndex() == 0 && e.absTick() == 480)
                .findFirst().orElseThrow().micros();
        long t1 = events.stream().filter(e -> e.trackIndex() == 1 && e.absTick() == 480)
                .findFirst().orElseThrow().micros();
        assertEquals(500_000, t0);
        assertEquals(250_000, t1);
        db.close();
    }
}
