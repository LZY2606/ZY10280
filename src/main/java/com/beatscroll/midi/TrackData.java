package com.beatscroll.midi;

import java.util.List;

/** 一条 MTrk 轨道的解析结果。 */
public record TrackData(
        int trackIndex,
        int chunkOffset,
        long declaredLength,
        long actualLength,
        List<MidiEvent> events) {
}
