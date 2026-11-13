package tv.anitv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/** Only identical playback records collapse; provider/quality captions remain distinct. */
final class SourceMerge {
    private final LinkedHashMap<String, PlaySource> values = new LinkedHashMap<>();
    void addAll(List<PlaySource> sources) {
        for (PlaySource source : sources) {
            String key = source.url + "\n" + source.caption + "\n" + source.type + "\n" + new TreeMap<>(source.headers);
            values.putIfAbsent(key, source);
        }
    }
    List<PlaySource> snapshot() { return new ArrayList<>(values.values()); }
}
