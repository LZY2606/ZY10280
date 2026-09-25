package com.beatscroll;

import com.beatscroll.fixtures.Fixtures;
import com.beatscroll.midi.MidiParser;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.TempoMap;
import com.beatscroll.midi.Timebase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempoMapTest {

    private static ParseResult parse(String fixture) {
        return new MidiParser().parse(Fixtures.all().get(fixture));
    }

    @Test
    void piecewiseIntegrationAcrossTempoSegments() {
        ParseResult r = parse("tempo-conflict.mid");
        TempoMap map = TempoMap.build(480, r.allEvents());
        assertEquals(0, map.microsAt(0));
        // tick 480 前走完第一段：480 * 1_000_000 / 480
        assertEquals(1_000_000, map.microsAt(480));
        // tick 480 起候选首个为 500_000 → tick 960 = 1_000_000 + 480*500_000/480
        assertEquals(1_500_000, map.microsAt(960));
    }

    @Test
    void sameTickCandidatesKeepFileOrderAndFlagAmbiguity() {
        ParseResult r = parse("tempo-conflict.mid");
        TempoMap map = TempoMap.build(480, r.allEvents());
        List<TempoMap.Point> points = map.points();
        assertEquals(2, points.size());
        TempoMap.Point conflict = points.get(1);
        assertEquals(480, conflict.tick());
        assertEquals(2, conflict.candidates().size());
        // 文件顺序：轨 0 的 500_000 在前，轨 1 的 250_000 在后
        assertEquals(500_000, conflict.candidates().get(0).tempoUsPerQuarter());
        assertEquals(250_000, conflict.candidates().get(1).tempoUsPerQuarter());
        assertEquals(0, conflict.candidates().get(0).trackIndex());
        assertEquals(1, conflict.candidates().get(1).trackIndex());
        assertTrue(conflict.candidates().get(0).seq() < conflict.candidates().get(1).seq());
        // 取值不同 → 歧义显式标记
        assertTrue(conflict.ambiguous());
        assertTrue(map.hasAmbiguity());
        assertFalse(points.get(0).ambiguous());
    }

    @Test
    void format2BuildsPerTrackTempoMaps() {
        ParseResult r = parse("format2-independent.mid");
        Timebase tb = Timebase.build(r.header(), r.tracks());
        assertTrue(tb.diagnostics().stream()
                .anyMatch(d -> d.code().equals("FORMAT2_INDEPENDENT_TIMELINES")));
        // 同一 tick 在不同轨按各自 tempo 换算
        assertEquals(500_000, tb.microsAt(0, 480));
        assertEquals(250_000, tb.microsAt(1, 480));
    }

    @Test
    void defaultTempoAppliesBeforeFirstTempoEvent() {
        ParseResult r = parse("running-status-meta.mid");
        TempoMap map = TempoMap.build(480, r.allEvents());
        assertTrue(map.points().isEmpty());
        assertEquals(500_000, map.microsAt(480));
    }
}
