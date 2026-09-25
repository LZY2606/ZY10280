package com.beatscroll;

import com.beatscroll.fixtures.Fixtures;
import com.beatscroll.midi.MidiEvent;
import com.beatscroll.midi.MidiParser;
import com.beatscroll.midi.MidiWriter;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.TrackData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** parse → export → parse 的语义保持：事件种类、tick、参数、payload 全部一致。 */
class RoundTripTest {

    @Test
    void parseExportParsePreservesSemanticsForAllFixtures() {
        MidiParser parser = new MidiParser();
        for (Map.Entry<String, byte[]> fixture : Fixtures.all().entrySet()) {
            ParseResult first = parser.parse(fixture.getValue());
            byte[] exported = MidiWriter.write(first.header(), first.tracks());
            ParseResult second = parser.parse(exported);

            assertEquals(first.header().format(), second.header().format(), fixture.getKey());
            assertEquals(first.header().divisionRaw(), second.header().divisionRaw(), fixture.getKey());
            assertEquals(first.tracks().size(), second.tracks().size(), fixture.getKey());
            for (int t = 0; t < first.tracks().size(); t++) {
                assertEquals(semantics(first.tracks().get(t)), semantics(second.tracks().get(t)),
                        fixture.getKey() + " 轨 " + t);
            }
        }
    }

    @Test
    void exportedFileParsesWithoutErrors() {
        MidiParser parser = new MidiParser();
        for (Map.Entry<String, byte[]> fixture : Fixtures.all().entrySet()) {
            ParseResult first = parser.parse(fixture.getValue());
            ParseResult second = parser.parse(MidiWriter.write(first.header(), first.tracks()));
            assertEquals(List.of(), second.diagnostics().stream()
                    .filter(d -> d.severity() == com.beatscroll.midi.Diagnostic.Severity.ERROR)
                    .toList(), fixture.getKey());
        }
    }

    /** 语义元组：忽略 EOT（导出器可能补写）与字节级细节。 */
    private static List<String> semantics(TrackData track) {
        List<String> out = new ArrayList<>();
        for (MidiEvent e : track.events()) {
            if (e.isEndOfTrack()) {
                continue;
            }
            out.add(e.kind() + "@" + e.absTick()
                    + " st=" + e.status()
                    + " ch=" + e.channel()
                    + " d1=" + e.data1()
                    + " d2=" + e.data2()
                    + " meta=" + e.metaType()
                    + " p=" + e.payloadHex());
        }
        return out;
    }
}
