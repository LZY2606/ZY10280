package com.beatscroll.midi;

import java.util.List;

/** 整个文件的解析结果：header 可能为 null（头部损坏时）。 */
public record ParseResult(MidiHeader header, List<TrackData> tracks, List<Diagnostic> diagnostics) {

    public List<MidiEvent> allEvents() {
        return tracks.stream().flatMap(t -> t.events().stream()).toList();
    }

    public boolean hasError() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }
}
