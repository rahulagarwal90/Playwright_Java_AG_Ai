package com.ai.healer.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a LocatorHealer suggestion to a page object source file: a minimal, surgical
 * replacement of one locator field's string literal, nothing else. Doesn't run tests, doesn't
 * judge whether the suggested locator is actually a good fix, doesn't touch git - it only edits
 * a file, and only after confirming the target line really looks like a locator declaration.
 * Works on the raw file text (not Files.readAllLines/write) so a file with no trailing newline,
 * or with line endings other than plain "\n", round-trips byte-for-byte outside the one line
 * that actually changes.
 */
public class PageObjectPatcher {

    public static class PatchResult {
        public final boolean applied;
        public final String reason;

        private PatchResult(boolean applied, String reason) {
            this.applied = applied;
            this.reason = reason;
        }

        static PatchResult applied(String reason) {
            return new PatchResult(true, reason);
        }

        static PatchResult notApplied(String reason) {
            return new PatchResult(false, reason);
        }
    }

    // Matches exactly the convention page object locator fields use in this codebase, e.g.
    //     private final String loginButton = "#login-button";
    // Group 1 = everything up to and including the opening quote, group 2 = the existing
    // selector value, group 3 = the closing quote, semicolon, and any trailing whitespace.
    private static final Pattern LOCATOR_FIELD_LINE =
            Pattern.compile("^(\\s*private\\s+final\\s+String\\s+\\w+\\s*=\\s*\")([^\"]*)(\"\\s*;\\s*)$");

    public PatchResult patch(Path targetFile, int lineNumber, String newSelector) {
        if (targetFile == null) {
            return PatchResult.notApplied("No target file path was provided");
        }
        if (!Files.isRegularFile(targetFile)) {
            return PatchResult.notApplied("File does not exist: " + targetFile);
        }
        if (newSelector == null || newSelector.isBlank()) {
            return PatchResult.notApplied("No replacement selector was provided");
        }

        // A model-suggested selector may use double quotes (e.g. echoing a full Java statement
        // like page.locator("#id") instead of the requested bare selector, or simply preferring
        // double-quoted attribute values). Normalize to this codebase's single-quote convention
        // before validating, rather than rejecting an otherwise-fixable suggestion outright.
        newSelector = newSelector.replace('"', '\'');
        if (newSelector.contains("\"")) {
            // Unreachable today - the replace above removes every double quote - but kept as a
            // defensive check for some other genuinely unrepresentable character in the future,
            // since a raw double quote here would still prematurely close the string literal.
            return PatchResult.notApplied(
                    "Replacement selector contains a double quote, which would break the string "
                    + "literal it's inserted into: " + newSelector);
        }

        String content;
        try {
            content = Files.readString(targetFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return PatchResult.notApplied("Failed to read " + targetFile + ": " + e.getMessage());
        }

        String[] lines = content.split("\n", -1);
        if (lineNumber < 1 || lineNumber > lines.length) {
            return PatchResult.notApplied("Line " + lineNumber + " is out of range for " + targetFile
                    + " (file has " + lines.length + " lines)");
        }

        String originalLine = lines[lineNumber - 1];
        Matcher matcher = LOCATOR_FIELD_LINE.matcher(originalLine);
        if (!matcher.matches()) {
            return PatchResult.notApplied("Line " + lineNumber + " of " + targetFile
                    + " doesn't look like a locator field declaration (expected "
                    + "'private final String <name> = \"...\";'), found: " + originalLine.strip());
        }

        String oldValue = matcher.group(2);
        if (oldValue.equals(newSelector)) {
            return PatchResult.notApplied("Line " + lineNumber + " of " + targetFile
                    + " already holds \"" + newSelector + "\" - nothing to patch");
        }

        lines[lineNumber - 1] = matcher.group(1) + newSelector + matcher.group(3);

        try {
            Files.writeString(targetFile, String.join("\n", lines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return PatchResult.notApplied("Failed to write " + targetFile + ": " + e.getMessage());
        }

        return PatchResult.applied("Replaced \"" + oldValue + "\" with \"" + newSelector + "\" at "
                + targetFile + ":" + lineNumber);
    }
}
