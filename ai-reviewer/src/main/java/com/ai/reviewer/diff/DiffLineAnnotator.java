package com.ai.reviewer.diff;

/**
 * This class only does one thing: it tags every added line in a diff with the real file line
 * number it will end up on, like "[Line 42] + someCode();". It doesn't fetch diffs, talk to
 * Ollama, or know about findings — it just marks up diff text so whoever reads the diff next
 * (the Ollama model) can say exactly which line a problem is on.
 */
public class DiffLineAnnotator {

    // Adds a "[Line N]" tag to every added line so the model can report back an exact line number.
    public static String annotate(String diffText) {
        if (diffText == null || diffText.isEmpty()) {
            return diffText;
        }

        StringBuilder annotated = new StringBuilder();
        String[] lines = diffText.split("\n");
        int currentNewLine = -1;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                // A hunk header always names a new-line start; if it somehow doesn't, leave the
                // running line count exactly as it was rather than resetting it.
                Integer newLineStart = parseNewLineStart(line);
                if (newLineStart != null) {
                    currentNewLine = newLineStart;
                }
                annotated.append(line).append("\n");
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                if (currentNewLine > 0) {
                    annotated.append("[Line ").append(currentNewLine).append("] ").append(line).append("\n");
                    currentNewLine++;
                } else {
                    annotated.append(line).append("\n");
                }
            } else {
                annotated.append(line).append("\n");
                if (line.startsWith(" ") && currentNewLine > 0) {
                    currentNewLine++;
                }
            }
        }

        return annotated.toString();
    }

    // Reads the starting line number out of a diff hunk header, e.g. "@@ -13,6 +13,30 @@" -> 13.
    // Returns null if the header has no "+" segment at all (so the caller knows not to touch
    // its running count); returns -1 if a "+" segment is present but isn't a parseable number.
    private static Integer parseNewLineStart(String hunkHeader) {
        String[] parts = hunkHeader.split(" ");
        for (String part : parts) {
            if (part.startsWith("+")) {
                String[] range = part.substring(1).split(",");
                try {
                    return Integer.parseInt(range[0]);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return null;
    }
}
