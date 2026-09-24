package scroll;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import scroll.fixtures.Fixtures;
import scroll.midi.Diagnostic;
import scroll.midi.MidiEvent;
import scroll.midi.MidiFile;
import scroll.midi.MidiParser;

class ParserTest {

    private static MidiFile parse(String fixture) {
        return MidiParser.parse(Fixtures.all().get(fixture), fixture);
    }

    private static boolean hasDiag(MidiFile f, String code) {
        return f.diagnostics.stream().anyMatch(d -> d.code().equals(code));
    }

    @Test
    void parsesHeaderFormats() {
        assertEquals(0, parse("smpte-dropframe").format);
        assertEquals(1, parse("demo-ppqn").format);
        assertEquals(2, parse("format2-independent").format);
        MidiFile f = parse("demo-ppqn");
        assertEquals(2, f.ntracks);
        assertEquals(2, f.tracks.size());
        assertEquals(480, f.division.ppqn());
        assertFalse(f.division.smpte());
    }

    @Test
    void smpteDropFrameDivision() {
        MidiFile f = parse("smpte-dropframe");
        assertTrue(f.division.smpte());
        assertEquals(29, f.division.fps());
        assertTrue(f.division.dropFrame());
        assertEquals(29.97, f.division.fpsValue(), 1e-9);
        assertEquals(40, f.division.ticksPerFrame());
    }

    @Test
    void runningStatusOnlyFollowsChannelVoice() {
        MidiFile f = parse("running-status-meta");
        List<MidiEvent> events = f.tracks.get(0).events;
        assertEquals(6, events.size());
        // 第二条事件沿用 running status
        MidiEvent running = events.get(1);
        assertTrue(running.runningStatus);
        assertEquals(0, running.status);
        assertEquals(0x90, running.effectiveStatus);
        assertEquals(2, running.eventLength); // 仅 2 数据字节，无状态字节
        // meta 清除 running status：显式诊断
        assertTrue(hasDiag(f, "RUNNING_STATUS_CLEARED"));
        // meta 之后裸数据字节 → 违规
        assertTrue(hasDiag(f, "RUNNING_STATUS_VIOLATION"));
        // 显式重写状态后 running 恢复
        assertTrue(events.get(4).runningStatus);
        assertEquals(0x90, events.get(3).effectiveStatus);
    }

    @Test
    void sysexAcrossBoundary() {
        MidiFile f = parse("sysex-multiblock");
        List<MidiEvent> events = f.tracks.get(0).events;
        assertEquals(MidiEvent.Kind.SYSEX, events.get(0).kind);
        assertEquals(0xF0, events.get(0).effectiveStatus);
        assertArrayEquals(new byte[]{0x43, 0x12, 0x00, 0x3E, 0x7F}, events.get(0).data);
        assertEquals(0xF7, events.get(1).effectiveStatus); // escape 续传
        assertArrayEquals(new byte[]{0x01, 0x02, (byte) 0xF7}, events.get(1).data);
        assertEquals(24, events.get(1).delta);
    }

    @Test
    void eventRawRangesMatchSource() {
        MidiFile f = parse("demo-ppqn");
        for (var track : f.tracks) {
            for (MidiEvent ev : track.events) {
                // raw 恰好覆盖 [deltaOffset, eventOffset+eventLength)
                assertEquals(ev.eventOffset - ev.deltaOffset + ev.eventLength, ev.raw.length);
                for (int i = 0; i < ev.raw.length; i++) {
                    assertEquals(f.source[ev.deltaOffset + i], ev.raw[i],
                            "事件 " + ev.seq + " 原始字节不一致");
                }
                assertTrue(ev.deltaOffset >= track.dataOffset);
            }
        }
    }

    @Test
    void vlqTooLongDiagnosed() {
        MidiFile f = parse("truncated-vlq");
        assertTrue(hasDiag(f, "VLQ_TOO_LONG"));
        assertEquals(1, f.tracks.get(0).events.size()); // 出错前的事件保留
    }

    @Test
    void trackLengthTruncationDiagnosed() {
        MidiFile f = parse("truncated-track");
        long count = f.diagnostics.stream()
                .filter(d -> d.code().equals("TRACK_TRUNCATED")).count();
        assertTrue(count >= 1, "应有 TRACK_TRUNCATED 诊断");
    }

    @Test
    void bytesAfterEotDiagnosed() {
        MidiFile f = parse("eot-tail");
        assertTrue(hasDiag(f, "BYTES_AFTER_EOT"));
        // EOT 之后的尾字节仍被解析为事件并保留范围
        assertEquals(3, f.tracks.get(0).events.size());
    }

    @Test
    void format2IndependentTimelineDiagnosed() {
        MidiFile f = parse("format2-independent");
        assertTrue(hasDiag(f, "FORMAT2_INDEPENDENT"));
    }

    @Test
    void deltaTimesAccumulate() {
        MidiFile f = parse("demo-ppqn");
        long[] ticks = f.tracks.get(1).events.stream().mapToLong(e -> e.absTick).toArray();
        assertArrayEquals(new long[]{0, 480, 480, 960, 1440, 1920, 1920}, ticks);
    }

    @Test
    void diagnosticsCarryOffsets() {
        MidiFile f = parse("running-status-meta");
        Diagnostic cleared = f.diagnostics.stream()
                .filter(d -> d.code().equals("RUNNING_STATUS_CLEARED")).findFirst().orElseThrow();
        assertTrue(cleared.offset() > 0);
        assertEquals(0, cleared.trackIndex());
    }
}
