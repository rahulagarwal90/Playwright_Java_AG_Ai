package com.ai.healer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ai.healer.patch.PageObjectPatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PageObjectPatcherTest {

    // Mirrors the real shape of playwright-tests/.../LoginPage.java closely enough to exercise
    // the patcher realistically, including surrounding lines that must stay untouched.
    private static final String PAGE_OBJECT_SOURCE =
            "package com.framework.pages.saucedemo;\n"
            + "\n"
            + "public class LoginPage extends BasePage {\n"
            + "\n"
            + "    // Locators\n"
            + "    private final String usernameInput = \"#user-name\";\n"
            + "    private final String passwordInput = \"#password\";\n"
            + "    private final String loginButton = \"#login-button-BROKEN-TEMP\";\n"
            + "\n"
            + "    public LoginPage(Page page) {\n"
            + "        super(page);\n"
            + "    }\n"
            + "}\n";

    @Test
    void appliesMinimalSurgicalEditAndPreservesEverythingElseByteForByte(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("LoginPage.java");
        Files.writeString(file, PAGE_OBJECT_SOURCE, StandardCharsets.UTF_8);

        PageObjectPatcher patcher = new PageObjectPatcher();
        PageObjectPatcher.PatchResult result = patcher.patch(file, 8, "#login-button");

        assertTrue(result.applied, result.reason);

        String expected = PAGE_OBJECT_SOURCE.replace(
                "private final String loginButton = \"#login-button-BROKEN-TEMP\";",
                "private final String loginButton = \"#login-button\";");
        String actual = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(expected, actual, "every byte except the patched line's locator value must be unchanged");
    }

    @Test
    void preservesFileWithNoTrailingNewline(@TempDir Path tempDir) throws IOException {
        String noTrailingNewline = PAGE_OBJECT_SOURCE.stripTrailing();
        Path file = tempDir.resolve("LoginPage.java");
        Files.writeString(file, noTrailingNewline, StandardCharsets.UTF_8);

        PageObjectPatcher patcher = new PageObjectPatcher();
        PageObjectPatcher.PatchResult result = patcher.patch(file, 8, "#login-button");

        assertTrue(result.applied, result.reason);
        String actual = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(actual.endsWith("\n"), "patch must not add a trailing newline the original file didn't have");
    }

    @Test
    void refusesWhenLineIsNotALocatorDeclaration(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("LoginPage.java");
        Files.writeString(file, PAGE_OBJECT_SOURCE, StandardCharsets.UTF_8);

        PageObjectPatcher patcher = new PageObjectPatcher();
        // Line 3 is "public class LoginPage extends BasePage {" - clearly not a locator field.
        PageObjectPatcher.PatchResult result = patcher.patch(file, 3, "#login-button");

        assertFalse(result.applied);
        assertTrue(result.reason.contains("doesn't look like a locator field declaration"), result.reason);
        assertEquals(PAGE_OBJECT_SOURCE, Files.readString(file, StandardCharsets.UTF_8),
                "file must be untouched when the patch is refused");
    }

    @Test
    void refusesWhenLineNumberIsOutOfRange(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("LoginPage.java");
        Files.writeString(file, PAGE_OBJECT_SOURCE, StandardCharsets.UTF_8);

        PageObjectPatcher patcher = new PageObjectPatcher();
        PageObjectPatcher.PatchResult result = patcher.patch(file, 999, "#login-button");

        assertFalse(result.applied);
        assertTrue(result.reason.contains("out of range"), result.reason);
    }

    @Test
    void refusesWhenFileDoesNotExist(@TempDir Path tempDir) {
        PageObjectPatcher patcher = new PageObjectPatcher();
        PageObjectPatcher.PatchResult result = patcher.patch(tempDir.resolve("DoesNotExist.java"), 8, "#login-button");

        assertFalse(result.applied);
        assertTrue(result.reason.contains("does not exist"), result.reason);
    }

    @Test
    void normalizesDoubleQuotesToSingleQuotesAndPatchesSuccessfully(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("LoginPage.java");
        Files.writeString(file, PAGE_OBJECT_SOURCE, StandardCharsets.UTF_8);

        PageObjectPatcher patcher = new PageObjectPatcher();
        // LocatorHealer's prompt asks for a bare selector, but nothing stops a model from
        // returning a double-quoted attribute selector instead of this codebase's single-quote
        // convention - that should be normalized and patched in, not refused outright.
        PageObjectPatcher.PatchResult result = patcher.patch(file, 8, "[data-test=\"login-button\"]");

        assertTrue(result.applied, result.reason);
        String expected = PAGE_OBJECT_SOURCE.replace(
                "private final String loginButton = \"#login-button-BROKEN-TEMP\";",
                "private final String loginButton = \"[data-test='login-button']\";");
        String actual = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(expected, actual,
                "double quotes in the suggestion should be normalized to single quotes, not refused");
    }

    @Test
    void refusesWhenNewValueIsIdenticalToExisting(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("LoginPage.java");
        Files.writeString(file, PAGE_OBJECT_SOURCE, StandardCharsets.UTF_8);

        PageObjectPatcher patcher = new PageObjectPatcher();
        PageObjectPatcher.PatchResult result = patcher.patch(file, 8, "#login-button-BROKEN-TEMP");

        assertFalse(result.applied);
        assertTrue(result.reason.contains("nothing to patch"), result.reason);
    }
}
