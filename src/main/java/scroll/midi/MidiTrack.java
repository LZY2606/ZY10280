package scroll.midi;

import java.util.ArrayList;
import java.util.List;

public final class MidiTrack {
    public final int index;
    public int chunkOffset = -1;   // 'MTrk' 在文件中的偏移
    public int dataOffset = -1;    // 事件数据起始偏移
    public int declaredLength = -1;
    public final List<MidiEvent> events = new ArrayList<>();

    public MidiTrack(int index) {
        this.index = index;
    }
}
