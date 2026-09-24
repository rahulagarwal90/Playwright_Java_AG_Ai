package com.ai.reviewer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class only drops FAILED findings whose suggested fix is already exactly what the reviewed
 * diff adds — the model sometimes "suggests" the very line the PR just wrote. It doesn't know
 * anything about Ollama or GitHub; it just compares findings against the diff's "+" lines.
 */
public class AlreadyAppliedFindingFilter {

    private static final Logger LOGGER = Logger.getLogger(AlreadyAppliedFindingFilter.class.getName());

    // A matching "+" line only counts if it's within this many lines of the finding's own line, so
    // the same text added elsewhere in the file can't hide a genuine finding.
    private static final int LINE_TOLERANCE = 2;

    // "@@ -a,b +c,d @@" (",b"/",d" are omitted when the count is 1) - group 1 is c, the new-file start line.
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private AlreadyAppliedFindingFilter() {
    }

    // Returns findings minus every FAILED one whose suggestedFix matches a "+" line added to the same file.
    public static List<ReviewFinding> filter(List<ReviewFinding> findings, String reviewedDiff) {
        Map<String, Map<String, List<Integer>>> addedLinesByFile = addedLinesByFile(reviewedDiff);
        List<ReviewFinding> kept = new ArrayList<>();
        for (ReviewFinding finding : findings) {
            if (isAlreadyApplied(finding, addedLinesByFile)) {
                LOGGER.info("[SUPPRESSED_ALREADY_APPLIED] file=" + finding.file
                        + " suggestedFix=" + finding.suggestedFix);
            } else {
                kept.add(finding);
            }
        }
        return kept;
    }

    private static boolean isAlreadyApplied(ReviewFinding finding, Map<String, Map<String, List<Integer>>> addedLinesByFile) {
        if (!"FAILED".equalsIgnoreCase(finding.status) || finding.file == null || finding.suggestedFix == null) {
            return false;
        }
        String fix = finding.suggestedFix.trim();
        // A multi-line suggestion can't equal any single added line - keep it.
        if (fix.isEmpty() || fix.contains("\n")) {
            return false;
        }
        // No usable line number means there's nothing to anchor the match to - keep it.
        if (finding.line <= 0) {
            return false;
        }
        String normalisedFix = normalise(fix);
        for (Map.Entry<String, Map<String, List<Integer>>> entry : addedLinesByFile.entrySet()) {
            if (pathsMatch(finding.file.trim(), entry.getKey())
                    && isNearFindingLine(entry.getValue().get(normalisedFix), finding.line)) {
                return true;
            }
        }
        return false;
    }

    // True if any new-file line number the matching "+" text was added at is within LINE_TOLERANCE of findingLine.
    private static boolean isNearFindingLine(List<Integer> addedAtLines, int findingLine) {
        if (addedAtLines == null) {
            return false;
        }
        for (int addedAt : addedAtLines) {
            if (Math.abs(addedAt - findingLine) <= LINE_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    // Diff paths are repo-relative; the model may report the same path or a shorter suffix of it.
    private static boolean pathsMatch(String findingFile, String diffFile) {
        String finding = findingFile.startsWith("./") ? findingFile.substring(2) : findingFile;
        return !finding.isEmpty() && (diffFile.equals(finding) || diffFile.endsWith("/" + finding));
    }

    // Trim, drop a trailing "//" comment only when it follows the last ";" (so a "//" inside a
    // string literal is never touched), then collapse whitespace runs to one space.
    static String normalise(String line) {
        String result = line.trim();
        int lastSemicolon = result.lastIndexOf(';');
        if (lastSemicolon >= 0) {
            int commentStart = result.indexOf("//", lastSemicolon);
            if (commentStart >= 0) {
                result = result.substring(0, commentStart);
            }
        }
        return result.trim().replaceAll("\\s+", " ");
    }

    // Maps each file in the diff (its "+++ b/" path) to the normalised text of every line it adds,
    // and each text to the new-file line number(s) it was added at.
    static Map<String, Map<String, List<Integer>>> addedLinesByFile(String diff) {
        Map<String, Map<String, List<Integer>>> result = new HashMap<>();
        if (diff == null) {
            return result;
        }
        String currentFile = null;
        boolean inHunk = false;
        // New-file line number the next "+" or context line has; -1 while unknown (unparseable
        // hunk header), in which case nothing is recorded so no finding can be dropped against it.
        int newLineNumber = -1;
        for (String line : diff.split("\n")) {
            if (line.startsWith("diff --git ")) {
                currentFile = null;
                inHunk = false;
            } else if (!inHunk && line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                currentFile = path.startsWith("b/") ? path.substring(2) : ("/dev/null".equals(path) ? null : path);
            } else if (line.startsWith("@@")) {
                inHunk = true;
                Matcher header = HUNK_HEADER.matcher(line);
                newLineNumber = header.find() ? Integer.parseInt(header.group(1)) : -1;
            } else if (inHunk && currentFile != null && line.startsWith("+")) {
                if (newLineNumber > 0) {
                    result.computeIfAbsent(currentFile, key -> new HashMap<>())
                            .computeIfAbsent(normalise(line.substring(1)), key -> new ArrayList<>())
                            .add(newLineNumber);
                    newLineNumber++;
                }
            } else if (inHunk && (line.startsWith(" ") || line.isEmpty())) {
                // Context line (an editor may strip a blank one's leading space): exists in the new
                // file too, so it consumes a line number. "-" and "\ No newline" lines don't.
                if (newLineNumber > 0) {
                    newLineNumber++;
                }
            }
        }
        return result;
    }
}
