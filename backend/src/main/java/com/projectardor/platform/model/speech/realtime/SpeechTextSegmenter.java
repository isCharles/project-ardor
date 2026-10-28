package com.projectardor.platform.model.speech.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Turns a growing text stream into speakable segments without waiting for the whole answer.
 * A segment closes on sentence punctuation or a line boundary; a length cap and a waiting cap
 * cover run-on text so playback still starts. Markup is stripped before the synthesiser sees it,
 * code blocks and bare links are dropped rather than read aloud, and an abandoned stream keeps
 * its unfinished tail unsent instead of presenting half a sentence as if it were complete.
 */
public final class SpeechTextSegmenter {

    private static final String HARD_BREAKS = "。！？；…!?;";
    private static final String SOFT_BREAKS = "，、,：: ";
    private static final String FENCE = "```";

    private final int maxSegmentCharacters;
    private final int minimumSegmentCharacters;
    private final LongSupplier clock;

    private final StringBuilder unsent = new StringBuilder();
    private long oldestPendingMillis;
    private int emittedSegments;
    private int emittedCharacters;
    private int droppedCodeCharacters;
    private int droppedLinks;

    public SpeechTextSegmenter(int maxSegmentCharacters, int minimumSegmentCharacters, LongSupplier clock) {
        this.maxSegmentCharacters = Math.max(24, maxSegmentCharacters);
        this.minimumSegmentCharacters = Math.max(1, minimumSegmentCharacters);
        this.clock = clock;
    }

    public static SpeechTextSegmenter create(LongSupplier clock) {
        return new SpeechTextSegmenter(120, 4, clock);
    }

    public synchronized List<String> accept(String delta) {
        if (delta == null || delta.isEmpty()) return List.of();
        if (unsent.isEmpty()) oldestPendingMillis = clock.getAsLong();
        unsent.append(delta);
        return drain(false);
    }

    /** Speaks text that has waited longer than the caller's threshold without reaching a boundary. */
    public synchronized List<String> flushWaiting(long maxWaitMillis) {
        if (unsent.isEmpty() || clock.getAsLong() - oldestPendingMillis < maxWaitMillis) return List.of();
        return forceTail();
    }

    /** Normal end of stream: the trailing sentence is spoken even when it lost its punctuation. */
    public synchronized List<String> finish() {
        return drain(true);
    }

    /** Abnormal end: pending text is dropped, because half a sentence must not be read as a finished one. */
    public synchronized void abandon() {
        unsent.setLength(0);
    }

    public synchronized int pendingCharacters() {
        return unsent.length();
    }

    public synchronized Statistics statistics() {
        return new Statistics(emittedSegments, emittedCharacters, droppedCodeCharacters, droppedLinks);
    }

    private List<String> drain(boolean complete) {
        List<String> segments = new ArrayList<>();
        int consumed = 0;
        int searchFrom = 0;
        while (consumed < unsent.length()) {
            int boundary = complete ? -1 : findBreak(searchFrom);
            boolean beyondCap = unsent.length() - consumed >= maxSegmentCharacters;
            int end;
            if (boundary >= 0 && boundary - consumed + 1 <= maxSegmentCharacters) end = boundary;
            else if (beyondCap || boundary >= 0) end = forcedEnd(consumed);
            else break;
            if (end <= consumed) break;
            String cleaned = clean(unsent.substring(consumed, end + 1));
            boolean keep = cleaned.isBlank() || cleaned.length() >= minimumSegmentCharacters || complete;
            if (keep) {
                if (!cleaned.isBlank()) {
                    segments.add(cleaned);
                    emittedSegments++;
                    emittedCharacters += cleaned.length();
                }
                consumed = end + 1;
                searchFrom = consumed;
            } else {
                searchFrom = end + 1;
            }
        }
        if (complete) {
            String tail = clean(unsent.substring(Math.min(consumed, unsent.length())));
            if (!tail.isBlank()) {
                segments.add(tail);
                emittedSegments++;
                emittedCharacters += tail.length();
            }
            consumed = unsent.length();
        }
        if (consumed > 0) unsent.delete(0, consumed);
        trimLeading();
        if (!unsent.isEmpty()) oldestPendingMillis = clock.getAsLong();
        return segments;
    }

    /** The waiting cap fires mid-sentence: prefer a clause boundary, otherwise speak what exists. */
    private List<String> forceTail() {
        int cut = lastSoftBreak(Math.min(unsent.length(), maxSegmentCharacters));
        if (cut < 0) cut = Math.min(unsent.length(), maxSegmentCharacters);
        String cleaned = clean(unsent.substring(0, cut));
        unsent.delete(0, cut);
        trimLeading();
        if (cleaned.isBlank()) return List.of();
        emittedSegments++;
        emittedCharacters += cleaned.length();
        if (!unsent.isEmpty()) oldestPendingMillis = clock.getAsLong();
        return List.of(cleaned);
    }

    private int findBreak(int from) {
        for (int index = Math.max(from, 0); index < unsent.length(); index++) {
            char current = unsent.charAt(index);
            if (current == '\n' || current == '\r') {
                if (!insideFence(index)) return index;
                continue;
            }
            if (HARD_BREAKS.indexOf(current) < 0) continue;
            if (isNumberFragment(index) || isEllipsis(index) || insideFence(index)) continue;
            if (isAbbreviation(index)) continue;
            return index;
        }
        return -1;
    }

    private int forcedEnd(int from) {
        int soft = lastSoftBreak(from + maxSegmentCharacters);
        return soft > from ? soft - 1 : Math.min(unsent.length(), from + maxSegmentCharacters) - 1;
    }

    private int lastSoftBreak(int limit) {
        int from = Math.min(unsent.length(), limit);
        for (int index = from - 1; index >= minimumSegmentCharacters; index--) {
            if (SOFT_BREAKS.indexOf(unsent.charAt(index)) >= 0 && !insideFence(index)) return index + 1;
        }
        return -1;
    }

    /** Keeps "3.5 秒" and "1.2.3" inside one segment instead of breaking on the dot. */
    private boolean isNumberFragment(int index) {
        char current = unsent.charAt(index);
        if (current != '.' && current != '!') return false;
        boolean previousDigit = index > 0 && Character.isDigit(unsent.charAt(index - 1));
        boolean nextDigit = index + 1 < unsent.length() && Character.isDigit(unsent.charAt(index + 1));
        return previousDigit && nextDigit;
    }

    private boolean isEllipsis(int index) {
        char current = unsent.charAt(index);
        if (current != '…' && current != '.') return false;
        if (index + 1 < unsent.length() && (unsent.charAt(index + 1) == '…' || unsent.charAt(index + 1) == '.')) {
            return true;
        }
        return index > 0 && (unsent.charAt(index - 1) == '…' || unsent.charAt(index - 1) == '.');
    }

    /** "U.S." and "v2.0 版本" should not close a sentence. */
    private boolean isAbbreviation(int index) {
        if (unsent.charAt(index) != '.') return false;
        boolean letterBefore = index > 0 && Character.isLetter(unsent.charAt(index - 1));
        boolean letterAfter = index + 1 < unsent.length() && Character.isLetter(unsent.charAt(index + 1));
        return letterBefore && letterAfter;
    }

    private boolean insideFence(int index) {
        int markers = 0;
        int cursor = 0;
        while (true) {
            int found = unsent.indexOf(FENCE, cursor);
            if (found < 0 || found > index) break;
            markers++;
            cursor = found + FENCE.length();
        }
        return markers % 2 == 1;
    }

    private void trimLeading() {
        int index = 0;
        while (index < unsent.length() && Character.isWhitespace(unsent.charAt(index))) index++;
        if (index > 0) unsent.delete(0, index);
    }

    /** Strips markup that would be read as noise and removes material that cannot be spoken aloud. */
    private String clean(String raw) {
        StringBuilder output = new StringBuilder(raw.length());
        int index = 0;
        while (index < raw.length()) {
            int fence = raw.indexOf(FENCE, index);
            if (fence < 0) {
                appendCleaned(output, raw.substring(index));
                break;
            }
            appendCleaned(output, raw.substring(index, fence));
            int close = raw.indexOf(FENCE, fence + FENCE.length());
            if (close < 0) {
                droppedCodeCharacters += raw.length() - fence;
                return output.toString().strip();
            }
            droppedCodeCharacters += close - fence;
            index = close + FENCE.length();
        }
        return output.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    private void appendCleaned(StringBuilder output, String text) {
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index]
                    .replaceFirst("^\\s{0,3}#{1,6}\\s*", "")
                    .replaceFirst("^\\s{0,3}>\\s*", "")
                    .replaceFirst("^\\s{0,3}[-*+]\\s+", "");
            String delinked = line.replaceAll("!?\\[([^\\]]*)]\\([^)]*\\)", "$1");
            if (delinked.length() != line.length()) droppedLinks++;
            line = stripInlineCodeAndEmphasis(delinked);
            String spoken = line.replaceAll("https?://\\S+", "");
            if (spoken.length() != line.length()) droppedLinks++;
            if (index > 0) output.append(' ');
            output.append(spoken);
        }
    }

    private String stripInlineCodeAndEmphasis(String line) {
        return line.replace("`", "")
                .replaceAll("\\*\\*|__|~~", "")
                .replaceAll("(?<![\\p{L}\\p{N}_])\\*(?=[^*\\s])", "")
                .replaceAll("(?<=\\s)\\*(?=$|[\\s\\p{P}])", "");
    }

    public record Statistics(int emittedSegments, int emittedCharacters, int droppedCodeCharacters, int droppedLinks) {
    }
}
