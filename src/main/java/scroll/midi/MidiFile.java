package scroll.midi;

import java.util.ArrayList;
import java.util.List;

public final class MidiFile {
    public String name = "";
    public int format;
    public int ntracks;
    public Division division;
    public final List<MidiTrack> tracks = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    public byte[] source = new byte[0];

    public List<MidiEvent> allEvents() {
        List<MidiEvent> all = new ArrayList<>();
        for (MidiTrack t : tracks) all.addAll(t.events);
        return all;
    }
}
