package com.beatscroll;

import com.beatscroll.fixtures.Fixtures;
import com.beatscroll.midi.MidiParser;
import com.beatscroll.midi.ParseResult;
import com.beatscroll.midi.Timebase;
import com.beatscroll.midi.Timing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmpteTest {

    @Test
    void dropFrameDivisionIsDecoded() {
        ParseResult r = new MidiParser().parse(Fixtures.all().get("smpte-drop-frame.mid"));
        Timing.Smpte smpte = assertInstanceOf(Timing.Smpte.class, r.header().timing());
        assertEquals(-29, smpte.fpsCode());
        assertEquals(40, smpte.ticksPerFrame());
        assertTrue(smpte.dropFrame());
        assertEquals(30000.0 / 1001.0, smpte.effectiveFps(), 1e-9);
    }

    @Test
    void dropFrameTicksConvertByFrameRateAndTicksPerFrame() {
        ParseResult r = new MidiParser().parse(Fixtures.all().get("smpte-drop-frame.mid"));
        Timebase tb = Timebase.build(r.header(), r.tracks());
        // 1200 tick = 30 帧 × 40 tpf；29.97fps 下 30 帧 = 1.001 s = 1_001_000 µs
        assertEquals(1_001_000, tb.microsAt(0, 1200));
        assertEquals(0, tb.microsAt(0, 0));
    }

    @Test
    void nonDropFrameRates() {
        assertEquals(1_000_000, new Timing.Smpte(-25, 40).microsAt(1000));
        assertEquals(500_000, new Timing.Smpte(-30, 60).microsAt(900));
        assertEquals(1_000_000, new Timing.Smpte(-24, 24).microsAt(576));
    }
}
