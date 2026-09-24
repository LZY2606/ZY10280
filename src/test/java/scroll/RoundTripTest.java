package scroll;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import scroll.fixtures.Fixtures;
import scroll.midi.MidiEvent;
import scroll.midi.MidiExporter;
import scroll.midi.MidiFile;
import scroll.midi.MidiParser;

/** parse → export → parse 的语义保持。 */
class RoundTripTest {

    private static final List<String> WELL_FORMED = List.of(
            "demo-ppqn", "smpte-dropframe", "tempo-same-tick",
            "sysex-multiblock", "format2-independent", "eot-tail");

    @Test
    void exportReproducesSourceBytes() {
        for (String name : WELL_FORMED) {
            byte[] source = Fixtures.all().get(name);
            byte[] exported = MidiExporter.export(MidiParser.parse(source, name));
            assertArrayEquals(source, exported, name + " 导出字节应与源一致");
        }
    }

    @Test
    void parseExportParsePreservesSemantics() {
        for (Map.Entry<String, byte[]> e : Fixtures.all().entrySet()) {
            MidiFile first = MidiParser.parse(e.getValue(), e.getKey());
            MidiFile second = MidiParser.parse(MidiExporter.export(first), e.getKey());
            assertEquals(first.format, second.format, e.getKey());
            assertEquals(first.division.raw(), second.division.raw(), e.getKey());
            assertEquals(first.tracks.size(), second.tracks.size(), e.getKey());
            for (int t = 0; t < first.tracks.size(); t++) {
                List<MidiEvent> a = first.tracks.get(t).events;
                List<MidiEvent> b = second.tracks.get(t).events;
                assertEquals(a.size(), b.size(), e.getKey() + " 轨 " + t);
                for (int i = 0; i < a.size(); i++) {
                    assertTrue(a.get(i).semanticallyEquals(b.get(i)),
                            e.getKey() + " 轨 " + t + " 事件 " + i);
                }
            }
        }
    }
}
