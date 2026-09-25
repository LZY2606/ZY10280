package com.beatscroll.midi;

/** MThd 头块：保留原始 division 值以便无损导出。 */
public record MidiHeader(int format, int ntrks, int divisionRaw, Timing timing) {

    public static MidiHeader of(int format, int ntrks, int divisionRaw) {
        Timing timing;
        if ((divisionRaw & 0x8000) != 0) {
            int fpsCode = (byte) ((divisionRaw >> 8) & 0xFF);
            int ticksPerFrame = divisionRaw & 0xFF;
            timing = new Timing.Smpte(fpsCode, ticksPerFrame);
        } else {
            timing = new Timing.Ppqn(divisionRaw);
        }
        return new MidiHeader(format, ntrks, divisionRaw, timing);
    }
}
