package scroll;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import scroll.fixtures.Fixtures;
import scroll.midi.MidiFile;
import scroll.midi.MidiParser;
import scroll.midi.TempoMap;

class TempoMapTest {

    private static MidiFile parse(String fixture) {
        return MidiParser.parse(Fixtures.all().get(fixture), fixture);
    }

    @Test
    void segmentedIntegrationAcrossTempoChanges() {
        // demo-ppqn: PPQN=480, tick 0 → 500000 µs/qn, tick 960 → 250000 µs/qn
        MidiFile f = parse("demo-ppqn");
        TempoMap map = TempoMap.build(f);
        assertEquals(0, map.tickToMicros(0));
        assertEquals(500_000, map.tickToMicros(480));          // 第一段：480 tick × 500000/480
        assertEquals(1_000_000, map.tickToMicros(960));        // 变速点
        assertEquals(1_250_000, map.tickToMicros(1440));       // 第二段：480 tick × 250000/480
        assertEquals(1_500_000, map.tickToMicros(1920));
    }

    @Test
    void defaultTempoAppliesBeforeFirstTempoEvent() {
        // running-status-meta 没有 tempo 事件 → 默认 500000 µs/qn
        MidiFile f = parse("running-status-meta");
        TempoMap map = TempoMap.build(f);
        assertEquals(120L * 500_000 / 480, map.tickToMicros(120));
    }

    @Test
    void sameTickTempoCandidatesKeepFileOrder() {
        // 两轨同 tick 0 各给 tempo：候选按文件顺序，生效者为先出现者
        MidiFile f = parse("tempo-same-tick");
        TempoMap map = TempoMap.build(f);
        List<TempoMap.Candidate> candidates = map.candidates();
        assertEquals(2, candidates.size());
        assertEquals(0, candidates.get(0).fileOrder());
        assertEquals(1, candidates.get(1).fileOrder());
        assertEquals(500_000, candidates.get(0).usPerQuarter());
        assertEquals(250_000, candidates.get(1).usPerQuarter());
        assertTrue(candidates.get(0).effective());
        assertFalse(candidates.get(1).effective());
        // 生效值取文件顺序第一（500000），且歧义被显式诊断
        assertEquals(480L * 500_000 / 480, map.tickToMicros(480));
        assertTrue(f.diagnostics.stream().anyMatch(d -> d.code().equals("TEMPO_AMBIGUITY")));
    }

    @Test
    void format2BuildsIndependentTempoMapsPerTrack() {
        MidiFile f = parse("format2-independent");
        TempoMap track0 = TempoMap.buildFor(f, 0);
        TempoMap track1 = TempoMap.buildFor(f, 1);
        assertEquals(500_000, track0.tickToMicros(480));
        assertEquals(250_000, track1.tickToMicros(480));
    }

    @Test
    void smpteDropFrameTickToMicros() {
        MidiFile f = parse("smpte-dropframe");
        // 29.97 fps × 40 tpf：tick 40 = 1 帧
        assertEquals(Math.round(40 * 1_000_000.0 / (29.97 * 40)), f.division.tickToMicros(40));
        assertEquals(Math.round(120 * 1_000_000.0 / (29.97 * 40)), f.division.tickToMicros(120));
        // 与 30fps 非 drop 的结果不同，证明 drop-frame 编码值生效
        assertNotEquals(Math.round(40 * 1_000_000.0 / (30.0 * 40)), f.division.tickToMicros(40));
    }
}
