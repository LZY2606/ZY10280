package com.beatscroll.midi;

import java.util.ArrayList;
import java.util.List;

/**
 * 全文件的时间换算。
 *
 * <p>PPQN：format 0/1 建立跨轨共享的 tempo map；format 2 各轨时间线独立，
 * tempo map 按轨分别建立并给出诊断。SMPTE：按帧率与 ticks-per-frame 直接换算，
 * 与 tempo 事件无关。
 */
public final class Timebase {

    private final MidiHeader header;
    private final TempoMap shared;
    private final List<TempoMap> perTrack;
    private final List<Diagnostic> diagnostics;

    private Timebase(MidiHeader header, TempoMap shared, List<TempoMap> perTrack,
                     List<Diagnostic> diagnostics) {
        this.header = header;
        this.shared = shared;
        this.perTrack = perTrack;
        this.diagnostics = diagnostics;
    }

    public static Timebase build(MidiHeader header, List<TrackData> tracks) {
        List<Diagnostic> diags = new ArrayList<>();
        if (header.timing() instanceof Timing.Smpte) {
            return new Timebase(header, null, null, diags);
        }
        int ppqn = ((Timing.Ppqn) header.timing()).ticksPerQuarter();
        if (header.format() == 2) {
            diags.add(new Diagnostic(Diagnostic.Severity.INFO, "FORMAT2_INDEPENDENT_TIMELINES", -1, -1,
                    "format 2 各轨时间线相互独立：tempo map 按轨分别建立，不做跨轨合并"));
            List<TempoMap> maps = new ArrayList<>();
            for (TrackData t : tracks) {
                maps.add(TempoMap.build(ppqn, t.events()));
            }
            return new Timebase(header, null, maps, diags);
        }
        List<MidiEvent> all = tracks.stream().flatMap(t -> t.events().stream()).toList();
        return new Timebase(header, TempoMap.build(ppqn, all), null, diags);
    }

    public long microsAt(int trackIndex, long tick) {
        if (header.timing() instanceof Timing.Smpte smpte) {
            return smpte.microsAt(tick);
        }
        TempoMap map = shared != null ? shared : perTrack.get(trackIndex);
        return map.microsAt(tick);
    }

    /** 用于展示的 tempo map（format 2 时按轨返回）。 */
    public TempoMap tempoMapFor(int trackIndex) {
        return shared != null ? shared : perTrack.get(trackIndex);
    }

    public boolean isSmpte() {
        return header.timing() instanceof Timing.Smpte;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
