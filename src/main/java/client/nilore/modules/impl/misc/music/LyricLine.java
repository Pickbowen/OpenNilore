package client.nilore.modules.impl.misc.music;

import java.util.List;

public record LyricLine(long timeMs, String text, List<Word> words) {

    public record Word(String text, long startMs, long durationMs) {
    }

    public LyricLine(long timeMs, String text) {
        this(timeMs, text, null);
    }

    public boolean hasWordTiming() {
        return words != null && !words.isEmpty();
    }
}
