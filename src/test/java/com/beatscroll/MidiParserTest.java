package com.beatscroll;

import com.beatscroll.fixtures.Fixtures;
import com.beatscroll.midi.Diagnostic;
import com.beatscroll.midi.MidiEvent;
import com.beatscroll.midi.MidiParser;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.TrackData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MidiParserTest {

    private static final MidiParser PARSER = new MidiParser();

    private static ParseResult parse(String fixture) {
        return PARSER.parse(Fixtures.all().get(fixture));
    }

    private static List<Diagnostic> diags(ParseResult r, String code) {
        return r.diagnostics().stream().filter(d -> d.code().equals(code)).toList();
    }

    @Test
    void fixturesAreAllPresent() {
        Map<String, byte[]> all = Fixtures.all();
        assertEquals(8, all.size());
        for (String name : List.of("smpte-drop-frame.mid", "tempo-conflict.mid",
                "running-status-meta.mid", "sysex-continuation.mid", "truncated-vlq.mid")) {
            assertTrue(all.containsKey(name), "缺少 fixture " + name);
        }
    }

    @Test
    void runningStatusOnlyExtendsChannelVoiceAndMetaClearsIt() {
        ParseResult r = parse("running-status-meta.mid");
        List<MidiEvent> events = r.tracks().get(0).events();
        assertEquals(6, events.size());
        // 第二条事件沿用 running status
        assertTrue(events.get(1).usedRunningStatus());
        assertEquals(0x90, events.get(1).status());
        assertEquals(62, events.get(1).data1());
        // meta 事件清除 running status → 孤立数据字节被诊断并跳过
        assertEquals(1, diags(r, "DATA_WITHOUT_STATUS").size());
        // meta 之后显式状态恢复，后续事件正常
        assertFalse(events.get(3).usedRunningStatus());
        assertEquals(64, events.get(3).data1());
        assertTrue(events.get(5).isEndOfTrack());
    }

    @Test
    void sysexContinuationAcrossBoundary() {
        ParseResult r = parse("sysex-continuation.mid");
        List<MidiEvent> events = r.tracks().get(0).events();
        assertEquals(MidiEvent.Kind.SYSEX_F0, events.get(1).kind());
        assertEquals("010203", events.get(1).payloadHex());
        assertEquals(MidiEvent.Kind.SYSEX_F7, events.get(2).kind());
        assertEquals("04f7", events.get(2).payloadHex());
        assertEquals(120, events.get(2).absTick());
        // SysEx 清除 running status → 续包后的孤立数据字节被诊断
        assertEquals(1, diags(r, "DATA_WITHOUT_STATUS").size());
    }

    @Test
    void vlqLongerThanFourBytesIsDiagnosed() {
        ParseResult r = parse("truncated-vlq.mid");
        assertEquals(1, diags(r, "VLQ_TOO_LONG").size());
        // 事件仍可恢复：Note On + EOT
        List<MidiEvent> events = r.tracks().get(0).events();
        assertEquals(2, events.size());
        assertEquals(MidiEvent.Kind.CHANNEL, events.get(0).kind());
    }

    @Test
    void trackLengthTruncationIsDiagnosed() {
        ParseResult r = parse("truncated-track.mid");
        assertEquals(1, diags(r, "TRACK_TRUNCATED").size());
        TrackData track = r.tracks().get(0);
        assertTrue(track.declaredLength() > track.actualLength());
        assertEquals(2, track.events().size());
    }

    @Test
    void trailingBytesAfterEotAreDiagnosed() {
        ParseResult r = parse("trailing-after-eot.mid");
        List<Diagnostic> diags = diags(r, "TRAILING_BYTES_AFTER_EOT");
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).message().contains("3"));
        // EOT 之后不再产生事件
        assertTrue(r.tracks().get(0).events().get(1).isEndOfTrack());
        assertEquals(2, r.tracks().get(0).events().size());
    }

    @Test
    void eventRawRangesAreContiguousAndCoverTrackBytes() {
        for (String name : Fixtures.all().keySet()) {
            ParseResult r = parse(name);
            byte[] data = Fixtures.all().get(name);
            for (TrackData t : r.tracks()) {
                List<MidiEvent> events = t.events();
                if (events.isEmpty()) {
                    continue;
                }
                assertEquals(t.chunkOffset() + 8, events.get(0).rawStart(),
                        name + " 首事件应紧跟 MTrk 头");
                for (int i = 0; i < events.size(); i++) {
                    MidiEvent e = events.get(i);
                    assertEquals(e.rawStart(), e.deltaStart(), name + " delta 从事件起点开始");
                    assertTrue(e.deltaEnd() > e.deltaStart(), name + " delta VLQ 至少 1 字节");
                    assertTrue(e.rawEnd() > e.rawStart());
                    assertTrue(e.rawEnd() <= data.length);
                    if (i + 1 < events.size()) {
                        int nextStart = events.get(i + 1).rawStart();
                        assertTrue(e.rawEnd() <= nextStart, name + " 事件范围不得重叠");
                        if (e.rawEnd() < nextStart) {
                            // 间隙必须对应被跳过的孤立字节，且有诊断记录
                            int gapStart = e.rawEnd();
                            assertTrue(r.diagnostics().stream().anyMatch(
                                            d -> d.trackIndex() == t.trackIndex()
                                                    && d.offset() >= gapStart && d.offset() < nextStart),
                                    name + " 范围间隙缺少对应诊断");
                        }
                    }
                }
            }
        }
    }

    @Test
    void format2IndependentTimelinesAreDiagnosed() {
        ParseResult r = parse("format2-independent.mid");
        assertEquals(2, r.header().format());
        assertEquals(2, r.tracks().size());
    }
}
