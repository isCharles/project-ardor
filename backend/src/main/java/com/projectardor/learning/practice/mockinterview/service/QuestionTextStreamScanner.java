package com.projectardor.learning.practice.mockinterview.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code {"questions":[{"questionText":"…"}]}} out of a token stream so the first question can
 * be spoken while the model is still writing the rest. Only {@code questionText} values are captured;
 * keys, sibling fields, nested containers and escapes are tracked but never spoken, so the same
 * document yields the same fragments no matter where the stream is cut.
 *
 * <p>Speech only. Persistence parses the finished document, because a stream that stops mid-value
 * leaves an unclosed question that must not be saved as though the model had written it.
 */
public final class QuestionTextStreamScanner {

    private static final String QUESTIONS_KEY = "questions";
    private static final String QUESTION_TEXT_KEY = "questionText";

    private final List<Frame> frames = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();

    private boolean inString;
    private boolean keyString;
    private boolean escaped;
    private int unicodeRemaining;
    private int unicodeValue;
    private int captureIndex = -1;
    private int completedQuestions;
    private boolean documentClosed;

    /** Captured question text; {@code completed} is emitted once, at that question's closing quote. */
    public record Fragment(int questionIndex, String text, boolean completed) {
    }

    public List<Fragment> feed(String delta) {
        List<Fragment> fragments = new ArrayList<>();
        for (int index = 0; index < delta.length(); index++) {
            consume(delta.charAt(index), fragments);
        }
        // Surface what has arrived even when the value is still open, so speech tracks generation.
        flushText(fragments);
        return fragments;
    }

    /** Questions whose closing quote has been seen; an unfinished value is not counted. */
    public int completedQuestions() {
        return completedQuestions;
    }

    public boolean documentClosed() {
        return documentClosed;
    }

    private void consume(char value, List<Fragment> fragments) {
        if (inString) {
            consumeInString(value, fragments);
            return;
        }
        switch (value) {
            case ' ', '\t', '\n', '\r' -> { }
            case '"' -> {
                beginValue();
                inString = true;
                keyString = !expectingValue();
                buffer.setLength(0);
                captureIndex = keyString ? -1 : questionTextSlot();
            }
            case ':' -> {
                Frame frame = current();
                if (frame != null && frame.object) frame.expectingValue = true;
            }
            case ',' -> {
                Frame frame = current();
                if (frame != null) {
                    frame.closeElement();
                    if (frame.object) frame.expectingValue = false;
                }
                flushText(fragments);
            }
            case '{' -> openContainer('{', fragments);
            case '[' -> openContainer('[', fragments);
            case '}' -> closeContainer(fragments);
            case ']' -> closeContainer(fragments);
            default -> beginValue();
        }
    }

    private void consumeInString(char value, List<Fragment> fragments) {
        if (unicodeRemaining > 0) {
            int digit = Character.digit(value, 16);
            if (digit < 0) {
                unicodeRemaining = 0;
                append(value);
            } else if (--unicodeRemaining == 0) {
                append((char) (unicodeValue * 16 + digit));
            } else {
                unicodeValue = unicodeValue * 16 + digit;
            }
            return;
        }
        if (escaped) {
            escaped = false;
            switch (value) {
                case 'n' -> append('\n');
                case 't' -> append('\t');
                case 'r' -> append('\r');
                case 'u' -> {
                    unicodeRemaining = 4;
                    unicodeValue = 0;
                }
                default -> append(value);
            }
            return;
        }
        if (value == '\\') {
            escaped = true;
            return;
        }
        if (value != '"') {
            append(value);
            return;
        }
        inString = false;
        Frame frame = current();
        if (frame != null) frame.expectingValue = false;
        if (keyString) {
            if (frame != null && frame.object) frame.pendingKey = buffer.toString();
        } else if (captureIndex >= 0) {
            flushText(fragments);
            completedQuestions = Math.max(completedQuestions, captureIndex + 1);
            fragments.add(new Fragment(captureIndex, "", true));
        }
        captureIndex = -1;
        buffer.setLength(0);
    }

    private void append(char value) {
        buffer.append(value);
    }

    private void flushText(List<Fragment> fragments) {
        if (captureIndex < 0 || buffer.isEmpty()) return;
        fragments.add(new Fragment(captureIndex, buffer.toString(), false));
        buffer.setLength(0);
    }

    private void openContainer(char opening, List<Fragment> fragments) {
        Frame parent = current();
        boolean questionsArray = opening == '[' && parent != null && parent.object && parent.rootObject
                && QUESTIONS_KEY.equals(parent.pendingKey);
        boolean questionItem = opening == '{' && parent != null && parent.questionsArray;
        int itemIndex = parent != null && !parent.object ? parent.elementIndex() : -1;
        beginValue();
        frames.add(new Frame(opening, questionsArray, questionItem, itemIndex, parent == null));
        if (parent != null && parent.object) parent.expectingValue = false;
    }

    private void closeContainer(List<Fragment> fragments) {
        if (frames.isEmpty()) {
            documentClosed = true;
            return;
        }
        Frame frame = current();
        frame.closeElement();
        frames.remove(frames.size() - 1);
        if (frames.isEmpty()) documentClosed = true;
        else flushText(fragments);
    }

    private void beginValue() {
        Frame frame = current();
        if (frame != null && !frame.object) frame.elementStarted = true;
    }

    private Frame current() {
        return frames.isEmpty() ? null : frames.get(frames.size() - 1);
    }

    private boolean expectingValue() {
        Frame frame = current();
        return frame != null && frame.object && frame.expectingValue;
    }

    private int questionTextSlot() {
        Frame frame = current();
        if (frame == null || !frame.questionItem || !QUESTION_TEXT_KEY.equals(frame.pendingKey)) return -1;
        return frame.itemIndex;
    }

    private static final class Frame {
        private final boolean object;
        private final boolean questionsArray;
        private final boolean questionItem;
        private final int itemIndex;
        private final boolean rootObject;
        private boolean expectingValue;
        private boolean elementStarted;
        private String pendingKey;
        private int children;

        private Frame(char opening, boolean questionsArray, boolean questionItem, int itemIndex, boolean rootObject) {
            this.object = opening == '{';
            this.questionsArray = questionsArray;
            this.questionItem = questionItem;
            this.itemIndex = itemIndex;
            this.rootObject = rootObject;
        }

        /** Index of the element currently open inside this frame. */
        private int elementIndex() {
            return children;
        }

        private void closeElement() {
            if (object) return;
            if (elementStarted) {
                children++;
                elementStarted = false;
            }
        }
    }
}
