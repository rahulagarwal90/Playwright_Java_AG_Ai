package com.ai.reviewer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LocalCodeReviewer reads the current git diff, filters changed code blocks,
 * optionally annotates new lines with file line numbers, and sends the result
 * to a local Ollama model for a code review response.
 */
public class LocalCodeReviewer {

    private static final Logger LOGGER = Logger.getLogger(LocalCodeReviewer.class.getName());
    private static final Pattern INLINE_STATUS_PATTERN = Pattern.compile("(?i)^(.+?):\\s*STATUS:\\s*\\[?(FAILED|PASSED)\\]?");
    private final HttpClient httpClient;

    /**
     * Default constructor for production use — wires up a real HttpClient so the reviewer
     * talks to Ollama and the GitHub API over actual network connections.
     */
    public LocalCodeReviewer() {
        this(HttpClient.newHttpClient());
    }

    /**
     * Constructor used by tests to inject a mock or stubbed HttpClient, so review logic can
     * be exercised without making real HTTP calls to Ollama or GitHub.
     */
    public LocalCodeReviewer(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Entry point for command-line execution.
     * Runs the reviewer and waits for the review task to complete.
     */

    public static void main(String[] args) {
        LocalCodeReviewer reviewer = new LocalCodeReviewer();
        int exitCode = 0;
        try {
            if (args.length > 0 && "learn".equalsIgnoreCase(args[0])) {
                reviewer.runLearn();
            } else {
                String reviewText = reviewer.runReview().join();
                List<ReviewFinding> findings = parseReviewFindings(reviewText);
                List<ReviewFinding> failedFindings = findings.stream()
                        .filter(finding -> "FAILED".equalsIgnoreCase(finding.status))
                        .toList();
                if (!failedFindings.isEmpty()) {
                    LOGGER.severe("[ERROR] AI Code Quality Gate detected failures.");
                    // isGitHubContext() already requires GITHUB_PR_NUMBER or CHANGE_ID (plus
                    // GITHUB_REPOSITORY/GITHUB_TOKEN), so it is false for any local terminal run
                    // that lacks real PR context, exactly as Jenkins provides it.
                    if (reviewer.isGitHubContext()) {
                        LOGGER.info("[INFO] Posting line-level PR review comments to GitHub.");
                        reviewer.postGitHubReviewComments(failedFindings);
                    } else {
                        LOGGER.info("[INFO] No PR context detected — findings printed to terminal only, GitHub posting skipped.");
                    }
                    exitCode = 1;
                } else {
                    LOGGER.info("[INFO] AI Code Quality Gate passed. No failed categories detected.");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("[ERROR] Exception during execution: " + e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    /**
     * Fetches the current git diff, filters it, and sends it to the local Ollama service.
     * Returns a CompletableFuture containing the review text or an error if git diff fails.
     */
    public CompletableFuture<String> runReview() {
        try {
            LOGGER.info(">>> Isolating local changes via 'git diff HEAD'...");
            String diffText = getGitDiff();
            String filteredDiff = filterDiff(diffText);
            if (filteredDiff.trim().isEmpty()) {
                LOGGER.info("[INFO] No git changes detected.");
                LOGGER.info(">>> Tip: Edit or stage files in git before running the code reviewer.");
                return CompletableFuture.completedFuture("No changes");
            }
            LOGGER.info(">>> Sending changes to local Ollama (model: qwen2.5-coder:14b)...");
            return sendToOllama(filteredDiff);
        } catch (Exception e) {
            LOGGER.severe("[ERROR] Failed to execute git diff: " + e.getMessage());
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    /**
     * Reads the local git diff against HEAD for the current repository.
     * Excludes the reviewer source file so the tool does not attempt to review itself.
     */
    public String getGitDiff() throws Exception {
        if (isGitHubContext()) {
            return getPullRequestDiffFromGitHub();
        }
        return getLocalGitDiff();
    }

    /**
     * Reads local working tree changes via git diff against HEAD.
     * Includes staged and unstaged changes while excluding this reviewer class.
     */
    private String getLocalGitDiff() throws Exception {
        // Natively targets both unstaged and staged changes in a single raw stream
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD", "--", ".", ":!**/LocalCodeReviewer.java");
        Process process = pb.start();
        String diffText;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            diffText = reader.lines().collect(Collectors.joining("\n"));
        }
        process.waitFor();
        return diffText;
    }

    /**
     * Fetches pull request diff content directly from GitHub when running in CI PR context.
     */
    private String getPullRequestDiffFromGitHub() throws Exception {
        String repo = getGitHubRepository();
        String prNumber = getGitHubPullRequestNumber();
        String apiBase = getGitHubApiBase();
        String token = getGitHubToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3.diff")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch PR diff from GitHub: " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    /**
     * Determines whether all required GitHub PR environment context is present.
     */
    private boolean isGitHubContext() {
        return Optional.ofNullable(System.getenv("GITHUB_REPOSITORY")).filter(s -> !s.isBlank()).isPresent()
                && (Optional.ofNullable(System.getenv("GITHUB_PR_NUMBER")).filter(s -> !s.isBlank()).isPresent()
                || Optional.ofNullable(System.getenv("CHANGE_ID")).filter(s -> !s.isBlank()).isPresent())
                && Optional.ofNullable(System.getenv("GITHUB_TOKEN")).filter(s -> !s.isBlank()).isPresent();
    }

    /**
     * Resolves repository identifier in owner/repo format from environment or Jenkins CHANGE_URL.
     */
    private String getGitHubRepository() {
        String repository = System.getenv("GITHUB_REPOSITORY");
        if (repository != null && !repository.isBlank()) {
            return repository;
        }
        String changeUrl = System.getenv("CHANGE_URL");
        if (changeUrl != null && !changeUrl.isBlank()) {
            try {
                URI uri = URI.create(changeUrl);
                String path = uri.getPath(); // e.g. /owner/repo/pull/123
                String[] segments = path.split("/");
                List<String> cleaned = new ArrayList<>();
                for (String s : segments) {
                    if (s != null && !s.isBlank()) cleaned.add(s);
                }
                if (cleaned.size() >= 2) {
                    // owner = cleaned[0], repo = cleaned[1]
                    return cleaned.get(0) + "/" + cleaned.get(1);
                }
            } catch (Exception e) {
                // fall through to error below
            }
        }
        throw new IllegalStateException("Missing required GitHub repository context: GITHUB_REPOSITORY or CHANGE_URL");
    }

    /**
     * Resolves pull request number from explicit env var or Jenkins CHANGE_ID fallback.
     */
    private String getGitHubPullRequestNumber() {
        String prNumber = System.getenv("GITHUB_PR_NUMBER");
        if (prNumber != null && !prNumber.isBlank()) {
            return prNumber;
        }
        prNumber = System.getenv("CHANGE_ID");
        if (prNumber != null && !prNumber.isBlank()) {
            return prNumber;
        }
        throw new IllegalStateException("Missing required GitHub PR number context: GITHUB_PR_NUMBER or CHANGE_ID");
    }

    /**
     * Returns GitHub token used for PR API calls.
     */
    private String getGitHubToken() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing required GitHub token: GITHUB_TOKEN");
        }
        return token;
    }

    /**
     * Resolves GitHub API base URL with a sensible default for github.com.
     */
    private String getGitHubApiBase() {
        return Optional.ofNullable(System.getenv("GITHUB_API_URL")).filter(s -> !s.isBlank()).orElse("https://api.github.com");
    }

    /**
     * Resolves the commit SHA used to anchor GitHub inline PR comments.
     *
     * Preference order:
     * 1. The PR's actual head SHA, fetched fresh from the GitHub API (repos/{repo}/pulls/{n}
     *    -> head.sha). This is authoritative and independent of how the CI job checked the
     *    branch out.
     * 2. GIT_COMMIT, set by the Jenkins git plugin to whatever commit is actually checked out.
     * 3. `git rev-parse HEAD` against the local working tree, for local/non-CI runs.
     *
     * MERGE-BEFORE-BUILD RISK: this method is only ever called from a GitHub PR context (see
     * postGitHubReviewComments), so (1) is expected to succeed in practice and (2)/(3) exist as
     * a defensive fallback. That fallback still carries risk: Jenkins multibranch pipelines
     * configured to "merge before build" check out a synthetic merge commit of the PR branch
     * into the target branch rather than the PR's real head commit. If GIT_COMMIT or a local
     * `git rev-parse HEAD` were used in that setup, the resulting SHA would not be one of the
     * PR's actual commits, and GitHub's create-review-comment API would reject it with a 422 —
     * a failure mode that cannot reproduce when testing locally against a normal checkout.
     */
    private String getGitCommitSha() throws Exception {
        try {
            return getGitHubPullRequestHeadSha();
        } catch (Exception e) {
            LOGGER.warning(() -> "Failed to resolve PR head SHA via GitHub API, falling back to GIT_COMMIT/local HEAD: "
                    + e.getMessage());
        }

        String gitCommit = System.getenv("GIT_COMMIT");
        if (gitCommit != null && !gitCommit.isBlank()) {
            return gitCommit;
        }
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        Process process = pb.start();
        String sha;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            sha = reader.lines().collect(Collectors.joining("\n")).trim();
        }
        process.waitFor();
        if (sha.isBlank()) {
            throw new RuntimeException("Cannot determine current Git commit SHA.");
        }
        return sha;
    }

    /**
     * Fetches the PR's real head commit SHA directly from the GitHub API. Unlike GIT_COMMIT or
     * a local `git rev-parse HEAD`, this is unaffected by whatever merge/checkout strategy the
     * CI job used, since it reads GitHub's own record of the PR branch's tip commit.
     */
    private String getGitHubPullRequestHeadSha() throws Exception {
        String repo = getGitHubRepository();
        String prNumber = getGitHubPullRequestNumber();
        String apiBase = getGitHubApiBase();
        String token = getGitHubToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch PR details from GitHub: " + response.statusCode() + " " + response.body());
        }

        JsonObject pr = new Gson().fromJson(response.body(), JsonObject.class);
        JsonObject head = (pr != null && pr.has("head")) ? pr.getAsJsonObject("head") : null;
        if (head == null || !head.has("sha")) {
            throw new RuntimeException("GitHub PR response did not include head.sha");
        }
        return head.get("sha").getAsString();
    }

    /**
     * Removes diff blocks that contain only metadata and no actual added or removed code.
     * This keeps the review payload focused on changed source lines only.
     */
    public static String filterDiff(String rawDiff) {
        if (rawDiff == null || rawDiff.trim().isEmpty()) {
            return "";
        }
        String[] blocks = rawDiff.split("(?=diff --git )");
        StringBuilder filtered = new StringBuilder();
        for (String block : blocks) {
            if (block.trim().isEmpty()) {
                continue;
            }
            boolean hasChanges = false;
            String[] lines = block.split("\n");
            for (String line : lines) {
                // Check for lines starting with + or - that are not part of diff file headers
                // (+++ or ---)
                if ((line.startsWith("+") && !line.startsWith("+++")) ||
                        (line.startsWith("-") && !line.startsWith("---"))) {
                    hasChanges = true;
                    break;
                }
            }
            if (hasChanges) {
                filtered.append(block);
            }
        }
        return filtered.toString();
    }

    /**
     * Adds [Line N] annotations to added lines in the diff so the reviewer can
     * see the exact destination line number for new code.
     */
    public static String annotateDiffWithLineNumbers(String diffText) {
        if (diffText == null || diffText.isEmpty()) {
            return diffText;
        }

        StringBuilder annotated = new StringBuilder();
        String[] lines = diffText.split("\n");
        int currentNewLine = -1;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                // Example header: @@ -13,6 +13,30 @@
                String[] parts = line.split(" ");
                for (String part : parts) {
                    if (part.startsWith("+")) {
                        String[] range = part.substring(1).split(",");
                        try {
                            currentNewLine = Integer.parseInt(range[0]);
                        } catch (NumberFormatException ignored) {
                            currentNewLine = -1;
                        }
                        break;
                    }
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

    /**
     * Parses the AI review feedback text into structured ReviewFinding objects.
     * 
     * This parser handles variable AI output formats:
     * - Categories with or without **bold** or [brackets]
     * - STATUS field that may appear inline (STATUS: FAILED) or on separate lines
     * - Multi-line fields (Problem, AI Suggested Fix can span multiple lines)
     * - File paths, line numbers, problem descriptions, and fixes
     * 
     * CRITICAL: GitHub PR inline comments require STATUS: FAILED to be detected so the
     * reviewer can post comments. This parser splits blocks by blank lines and extracts
     * each field robustly using regex patterns that work with different AI output layouts.
     * 
     * @param reviewText The raw feedback text from Ollama AI model
     * @return List of ReviewFinding objects with category, status, file, line, problem, and fix
     */
    static List<ReviewFinding> parseReviewFindings(String reviewText) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (reviewText == null || reviewText.isBlank()) {
            return findings;
        }

        // Normalize line endings and split blocks by blank lines (each block = one category's review)
        String normalized = reviewText.replace("\r\n", "\n").replace("\r", "\n");
        String[] blocks = normalized.split("\\n\\s*\\n+");

        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Extract category name from first line, removing decorations like **Category**:
            // This handles formats like "**Playwright Web Assertions:**" or "[Category]:"
            String[] lines = trimmed.split("\\n");
            String categoryLine = lines[0].trim();

            // Some model responses emit "Category: STATUS: FAILED" on one line.
            String status = "PASSED";
            Matcher inlineStatusMatcher = INLINE_STATUS_PATTERN.matcher(categoryLine);
            if (inlineStatusMatcher.find()) {
                categoryLine = inlineStatusMatcher.group(1).trim();
                status = inlineStatusMatcher.group(2).toUpperCase();
            }

            String category = categoryLine.replaceAll("^\\*\\*", "")
                    .replaceAll("\\*\\*$", "")
                    .replaceAll("^\\[", "")
                    .replaceAll("\\]$", "")
                    .replaceAll(":$", "")
                    .trim();

            // Extract STATUS field which may be inline (STATUS: FAILED) or on its own line
            // This is critical for detecting failed checks so GitHub comments can be posted
            if ("PASSED".equals(status)) {
                status = extractSingleLineField(trimmed, "STATUS")
                        .map(value -> value.replaceAll("\\[|\\]", "").trim().toUpperCase())
                        .orElse("PASSED");
            }

            // Extract file path, line number, problem description, and suggested fix
            // Using field extraction methods that handle multi-line content
            String file = extractSingleLineField(trimmed, "File").orElse("");
            String lineValue = extractSingleLineField(trimmed, "Line")
                    .or(() -> extractSingleLineField(trimmed, "Lines"))
                    .orElse("0");
            int lineNumber = 0;
            try {
                // Handle line ranges (e.g., "41, 42" or "350-360") by taking the first number
                String firstLineToken = lineValue.split("[,\\s-]+")[0].trim();
                lineNumber = Integer.parseInt(firstLineToken);
            } catch (Exception ignored) {
                lineNumber = 0;
            }
            String problem = extractFieldBody(trimmed, "Problem").orElse("");
            String suggestedFix = extractFieldBody(trimmed, "AI Suggested Fix").orElse("");

            ReviewFinding finding = new ReviewFinding();
            finding.category = category.replaceAll("\\*|\\[|\\]", "").trim();
            finding.status = status.isBlank() ? "PASSED" : status;
            finding.file = file.trim();
            finding.line = lineNumber;
            finding.problem = problem.trim();
            finding.suggestedFix = suggestedFix.trim();
            findings.add(finding);
        }

        return findings;
    }

    /**
     * Extracts a single-line field value from review text.
     * Example: "STATUS: FAILED" → returns "FAILED"
     * 
     * @param text The review block text to search
     * @param fieldName The field name (e.g., "STATUS", "Line", "File")
     * @return Optional containing the field value, or empty if not found
     */
    private static Optional<String> extractSingleLineField(String text, String fieldName) {
        Pattern fieldPattern = Pattern.compile("(?im)^\\s*" + Pattern.quote(fieldName) + ":\\s*(.*)$");
        Matcher matcher = fieldPattern.matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Extracts a potentially multi-line field value from review text.
     * Example: "Problem: ... content ...\nAI Suggested Fix: ..." → returns multi-line content before next field
     * 
     * Used for fields like "Problem" and "AI Suggested Fix" which may span multiple lines.
     * 
     * @param text The review block text to search
     * @param fieldName The field name (e.g., "Problem", "AI Suggested Fix")
     * @return Optional containing the field value including all lines until the next field, or empty if not found
     */
    private static Optional<String> extractFieldBody(String text, String fieldName) {
        Pattern fieldPattern = Pattern.compile("(?ims)" + Pattern.quote(fieldName) + ":\\s*(.*?)(?=^\\s*[A-Za-z0-9 _\\[\\]-]+?:\\s*|\\z)");
        Matcher matcher = fieldPattern.matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Posts all FAILED findings as GitHub inline comments and keeps posting on per-item failures.
     */
    private void postGitHubReviewComments(List<ReviewFinding> findings) throws Exception {
        String repo = getGitHubRepository();
        String prNumber = getGitHubPullRequestNumber();
        String apiBase = getGitHubApiBase();
        String commitSha = getGitCommitSha();
        List<String> changedFiles = getPullRequestChangedFiles(repo, prNumber, apiBase);

        int postedCount = 0;
        int failedCount = 0;

        for (ReviewFinding finding : findings) {
            Optional<String> resolvedPath = resolveReviewFindingPath(finding.file, changedFiles);
            if (finding.line <= 0 || resolvedPath.isEmpty()) {
                LOGGER.warning(() -> "Skipping invalid review finding for GitHub comment: file="
                        + finding.file + ", line=" + finding.line + ", category=" + finding.category);
                failedCount++;
                continue;
            }

            finding.file = resolvedPath.get();

            try {
                createGitHubPullRequestComment(repo, prNumber, apiBase, commitSha, finding);
                postedCount++;
            } catch (Exception ex) {
                LOGGER.warning(() -> "Failed to post GitHub comment for file="
                        + finding.file + ", line=" + finding.line + ": " + ex.getMessage());
                failedCount++;
            }
        }

        LOGGER.info("GitHub inline comments posting summary: posted=" + postedCount + ", failed=" + failedCount);

        if (postedCount == 0 && !findings.isEmpty()) {
            throw new RuntimeException("Failed to post any GitHub inline comments for FAILED findings.");
        }
    }

    /**
     * Retrieves changed file paths for the pull request so bare filenames from AI output
     * can be resolved to repository-relative paths required by GitHub inline comments API.
     */
    private List<String> getPullRequestChangedFiles(String repo, String prNumber, String apiBase) throws Exception {
        List<String> changedFiles = new ArrayList<>();
        Gson gson = new Gson();

        for (int page = 1; page <= 10; page++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("%s/repos/%s/pulls/%s/files?per_page=100&page=%d", apiBase, repo, prNumber, page)))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Authorization", "Bearer " + getGitHubToken())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch PR files from GitHub: " + response.statusCode() + " " + response.body());
            }

            JsonArray files = gson.fromJson(response.body(), JsonArray.class);
            if (files == null || files.isEmpty()) {
                break;
            }

            for (int i = 0; i < files.size(); i++) {
                JsonObject fileObj = files.get(i).getAsJsonObject();
                if (fileObj.has("filename")) {
                    changedFiles.add(normalizePath(fileObj.get("filename").getAsString()));
                }
            }

            if (files.size() < 100) {
                break;
            }
        }

        return changedFiles;
    }

    /**
     * Resolves AI-reported file identifiers to exact repository-relative paths.
     * Supports exact paths and unique basename matches (e.g. LocalCodeReviewer.java).
     */
    private Optional<String> resolveReviewFindingPath(String rawPath, List<String> changedFiles) {
        if (rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }

        String normalizedRawPath = normalizePath(rawPath);

        if (changedFiles.contains(normalizedRawPath)) {
            return Optional.of(normalizedRawPath);
        }

        List<String> suffixMatches = changedFiles.stream()
                .filter(path -> path.endsWith("/" + normalizedRawPath))
                .toList();
        if (suffixMatches.size() == 1) {
            return Optional.of(suffixMatches.get(0));
        }

        String rawFileName = fileNamePart(normalizedRawPath);
        List<String> fileNameMatches = changedFiles.stream()
                .filter(path -> fileNamePart(path).equals(rawFileName))
                .toList();
        if (fileNameMatches.size() == 1) {
            return Optional.of(fileNameMatches.get(0));
        }

        return Optional.empty();
    }

    /**
     * Normalizes file paths to repository-style separators and strips leading ./.
     */
    private String normalizePath(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "").trim();
    }

    /**
     * Extracts filename component from a path.
     */
    private String fileNamePart(String path) {
        int slashIndex = path.lastIndexOf('/');
        return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
    }

    /**
     * Posts a single AI review finding as an inline comment on a GitHub PR.
     * 
     * GitHub PR comment API requires:
     * - commit_id: the commit SHA where the comment should appear
     * - path: the file path relative to repo root
     * - line: the line number in the file (on the RIGHT/new side of the diff)
     * - body: the comment text
     * 
     * Uses java.util.logging for debug output so it doesn't interfere with production logs.
     * 
     * @param repo Repository in format "owner/repo"
     * @param prNumber The PR number
     * @param apiBase GitHub API base URL (usually https://api.github.com)
     * @param commitSha The commit SHA for this PR
     * @param finding The ReviewFinding containing file, line, problem, and fix
     * @throws Exception If GitHub API returns a non-2xx status code
     */
    private void createGitHubPullRequestComment(String repo,
                                                String prNumber,
                                                String apiBase,
                                                String commitSha,
                                                ReviewFinding finding) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("body", buildCommentBody(finding));
        payload.addProperty("path", finding.file);
        payload.addProperty("line", finding.line);
        payload.addProperty("side", "RIGHT");
        payload.addProperty("commit_id", commitSha);
        String jsonBody = new Gson().toJson(payload);
        
        // Log debug info before posting (useful for troubleshooting missing GitHub comments)
        LOGGER.fine(() -> "Posting PR comment to: " + repo + " PR:" + prNumber + " file:" + finding.file + " line:" + finding.line);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s/comments", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + getGitHubToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        
        // Log GitHub API response for debugging (status code and response body help identify why comments failed)
        LOGGER.fine(() -> "GitHub response: " + response.statusCode() + " body: " + response.body());
        
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new RuntimeException("GitHub PR comment creation failed: " + response.statusCode() + " " + response.body());
        }
    }

    /**
     * Builds the exact text posted as a GitHub inline PR comment for one FAILED finding —
     * the category, the problem explanation, and the suggested fix — in the fixed format
     * GitHub renders back to reviewers directly on the PR diff.
     */
    private String buildCommentBody(ReviewFinding finding) {
        return String.format("[AI CODE QUALITY GATE] %s FAILURE\nProblem: %s\nSuggested fix:\n%s",
                finding.category,
                finding.problem,
                finding.suggestedFix);
    }

    /**
     * "learn" mode: fetches existing PR review comments, keeps only the ones a human
     * tagged with "@ai-learn", converts each into a single imperative rule via the model,
     * and persists the results to the RuleStore so future review runs can apply them.
     */
    private void runLearn() throws Exception {
        if (!isGitHubContext()) {
            return;
        }

        String repo = getGitHubRepository();
        String prNumber = getGitHubPullRequestNumber();
        String apiBase = getGitHubApiBase();
        String token = getGitHubToken();
        String botUsername = System.getenv("GITHUB_BOT_USERNAME");

        // STEP 1 of the learning loop: pull down every comment that exists on this PR — not
        // just @ai-learn ones. GitHub's REST API has no server-side way to filter comments by
        // body text, so everything on the PR is fetched here first; the actual @ai-learn
        // filtering happens below, once the raw comment list is in hand.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/pulls/%s/comments", apiBase, repo, prNumber)))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch PR comments from GitHub: " + response.statusCode() + " " + response.body());
        }

        Gson gson = new Gson();
        JsonArray comments = gson.fromJson(response.body(), JsonArray.class);

        RuleStore ruleStore = new RuleStore();
        List<RuleStore.LearnedRule> rules = new ArrayList<>(ruleStore.load());

        int matchedCommentCount = 0;
        int learnedCount = 0;
        int botSkippedCount = 0;
        int failedExtractionCount = 0;
        if (comments != null) {
            for (int i = 0; i < comments.size(); i++) {
                JsonObject comment = comments.get(i).getAsJsonObject();
                String body = comment.has("body") ? comment.get("body").getAsString() : "";

                // FILTER 1 — opt-in signal: a comment only becomes a candidate rule if a human
                // deliberately prefixed it with "@ai-learn". This is what stops every ordinary PR
                // comment ("nice catch", "lgtm", unrelated discussion) from being turned into an
                // enforced rule — only feedback someone explicitly flagged as a lesson is learned.
                if (body == null || !body.startsWith("@ai-learn")) {
                    continue;
                }
                matchedCommentCount++;

                // FILTER 2 — self-learning guard: never learn from a comment authored by our own
                // bot account. If the reviewer ingested its own prior review output as though it
                // were human feedback, it would reinforce whatever mistakes produced that output
                // in the first place, so any comment whose author matches GITHUB_BOT_USERNAME is
                // discarded here before it ever reaches the model.
                String authorLogin = (comment.has("user") && comment.get("user").isJsonObject())
                        ? comment.getAsJsonObject("user").get("login").getAsString()
                        : "";
                if (botUsername != null && !botUsername.isBlank() && botUsername.equals(authorLogin)) {
                    botSkippedCount++;
                    LOGGER.info("[LEARN] Skipped comment from bot account: " + authorLogin);
                    continue;
                }

                // STEP 2: compress the surviving human comment down to a single imperative rule
                // using a separate, minimal model call (see extractRuleFromComment below) — this
                // is deliberately a different call, with a different prompt, than the one used to
                // review code in sendToOllama().
                //
                // This call is intentionally isolated in its own try/catch: it fails for reasons
                // unrelated to the other comments in this PR (e.g. a transient Ollama hiccup on
                // this one request), so one failure here must not discard every rule already
                // extracted earlier in the same loop. Failures are logged and the comment is
                // skipped; the loop — and the eventual ruleStore.save() below — continue.
                String rule;
                try {
                    rule = extractRuleFromComment(body);
                } catch (Exception ex) {
                    failedExtractionCount++;
                    LOGGER.info("[LEARN] Comment: " + body);
                    LOGGER.info("[LEARN] Skipped — rule extraction failed: " + ex.getMessage());
                    continue;
                }
                LOGGER.info("[LEARN] Comment: " + body);
                LOGGER.info("[LEARN] Extracted rule: " + rule);
                learnedCount++;

                RuleStore.LearnedRule learnedRule = new RuleStore.LearnedRule();
                learnedRule.rule = rule;
                learnedRule.sourceComment = body;
                learnedRule.learnedAt = Instant.now().toString();
                rules.add(learnedRule);
            }
        }

        LOGGER.info("[LEARN] Processed " + matchedCommentCount + " @ai-learn comment(s): "
                + learnedCount + " learned, " + (botSkippedCount + failedExtractionCount) + " skipped.");

        // STEP 3: persist every rule — previously-learned ones plus whatever was just extracted
        // above — to ai-reviewer/learned-rules.json. This file is meant to be committed to git,
        // not treated as a local-only cache: Jenkins runs the reviewer against a fresh checkout
        // of the branch on every build, so a rule only reaches Jenkins, and therefore only
        // affects future reviews, once this file has been committed and merged. A machine-local
        // cache would be invisible to CI and would silently stop applying rules there.
        ruleStore.save(rules);
    }

    /**
     * Calls the local Ollama model with a minimal, single-purpose prompt that converts
     * a human code review comment into one imperative rule.
     */
    private String extractRuleFromComment(String commentBody) throws Exception {
        Gson gson = new Gson();

        // This system prompt is intentionally tiny and has nothing to do with the code-review
        // system prompt built in sendToOllama(). The model sees only the human's comment and the
        // instruction "turn this into one rule" — no diff, no review categories — so its only
        // job here is compression, not judgment about code quality.
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Convert this code review comment into one imperative rule, one line only, output nothing else.");

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", commentBody);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        // Temperature 0 so the same human comment reliably extracts to the same rule text.
        // Note this isn't what makes re-running learn mode duplicate-safe — RuleStore
        // de-duplicates by the original comment text, not by this extracted rule — but it keeps
        // the learned rule set stable and predictable rather than drifting between runs.
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.0);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "qwen2.5-coder:14b");
        payload.addProperty("stream", false);
        payload.add("messages", messages);
        payload.add("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama non-200 status code for learn request: " + response.statusCode() + " " + response.body());
        }

        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
        String content;
        if (jsonResponse.has("message")) {
            content = jsonResponse.getAsJsonObject("message").get("content").getAsString();
        } else {
            content = jsonResponse.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        }
        return content.trim();
    }

    /**
     * Represents a single AI code review finding with category, status, file, line, problem, and suggested fix.
     * 
     * Package-visible (not private) to allow unit testing of the parser. Each finding becomes a GitHub PR inline comment
     * if status is FAILED.
     */
    static class ReviewFinding {
        String category;
        String status;
        String file;
        int line;
        String problem;
        String suggestedFix;
    }

    /**
     * Sends the prepared diff text to the local Ollama HTTP API and returns the model response.
     * The model is expected to return a review string that is printed to stdout.
     */
    public CompletableFuture<String> sendToOllama(String diffText) {
        String annotatedDiff = annotateDiffWithLineNumbers(diffText);

        // String Hygiene Rule: Strictly apply string sanitization on the raw diff text
        // before payload assembly
        String sanitizedDiff = annotatedDiff
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        String systemPrompt = """
                You are an exceptionally strict automated Code Reviewer specializing in Java Playwright test frameworks.
                Review the provided plain text modifications line-by-line.

                CRITICAL SORTING RULES:
                - Assess each code change independently. Place a defect strictly in its single most relevant category. Do not repeat issues.
                - Playwright Web Assertions: Only flag legacy assertions (e.g., plain java assert, JUnit, or TestNG assertions).
                - Locator Robustness: Only flag brittle locators (e.g., absolute XPaths, long dynamic CSS).
                - Hardcoded Configurations: Only flag hardcoded synchronizations (e.g., Thread.sleep).
                - Logging: Only flag plain standard output statements (e.g., System.out.println, printStackTrace).

                UNIVERSAL OUTPUT FORMAT:
                - If a category passes, print exactly: [Category Name]: STATUS: [PASSED]
                - If a category fails, print exactly:
                   [Category Name]: STATUS: [FAILED]
                   File: [Provide the file path]
                   Line: [Provide the line number if visible]
                   Problem: [Clear explanation of why the code violates automation best practices, in 1-2 sentences]
                   AI Suggested Fix:
                   [Provide the exact, syntactically correct Java code snippet that replaces the bad code completely using active variables like testContext.getPage(). Do not use markdown backticks or asterisks. Limit any explanation to 1-2 sentences.]
                """;

        // STEP 4 (read side of the learning loop): every review run — including ones on a
        // completely different PR than the one that originally taught a rule — loads whatever
        // has accumulated in ai-reviewer/learned-rules.json so far. This only sees rules that
        // were committed and merged (see the commit-to-git note on RuleStore's save()); anything
        // learned locally but not yet pushed/merged will not show up here on a fresh checkout.
        List<RuleStore.LearnedRule> learnedRules = new RuleStore().load();
        if (!learnedRules.isEmpty()) {
            // STEP 5 — the exact splice point: learned rules are inserted as a new
            // "LEARNED RULES:" section immediately before "UNIVERSAL OUTPUT FORMAT:" — i.e.
            // after the fixed category rules above, but before the model is told how to format
            // its answer, so the extra rules read like additional review criteria rather than
            // output-formatting instructions.
            //
            // Before the splice, this part of the prompt reads:
            //   ...
            //   - Logging: Only flag plain standard output statements (e.g., System.out.println, printStackTrace).
            //
            //   UNIVERSAL OUTPUT FORMAT:
            //   - If a category passes, print exactly: [Category Name]: STATUS: [PASSED]
            //   ...
            //
            // After the splice (example, with two learned rules), it reads:
            //   ...
            //   - Logging: Only flag plain standard output statements (e.g., System.out.println, printStackTrace).
            //
            //   LEARNED RULES:
            //   - Use a proper logger instead of System.out.println, even in test setup code.
            //   - Never hardcode Thread.sleep; use Playwright's built-in waiting instead.
            //
            //   UNIVERSAL OUTPUT FORMAT:
            //   - If a category passes, print exactly: [Category Name]: STATUS: [PASSED]
            //   ...
            StringBuilder learnedRulesSection = new StringBuilder("LEARNED RULES:\n");
            for (RuleStore.LearnedRule learnedRule : learnedRules) {
                learnedRulesSection.append("- ").append(learnedRule.rule).append("\n");
            }
            learnedRulesSection.append("\n");
            systemPrompt = systemPrompt.replace("UNIVERSAL OUTPUT FORMAT:", learnedRulesSection + "UNIVERSAL OUTPUT FORMAT:");
        }
        // When learnedRules is empty, the block above never executes, so systemPrompt is left
        // exactly as the text block defined above — byte-for-byte identical to what this method
        // produced before the learning loop existed. Nothing downstream (the sanitization below,
        // or JSON payload assembly) can distinguish "learning loop not present" from "learning
        // loop present but learned-rules.json is empty or missing."

        String sanitizedSystemPrompt = systemPrompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        // Assemble raw JSON manually applying the sanitization
        String rawJson = "{"
                + "\"model\":\"qwen2.5-coder:14b\"," // Upgraded brain
                + "\"stream\":false,"
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + sanitizedSystemPrompt + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + sanitizedDiff + "\"}"
                + "],"
                + "\"options\":{"
                + "\"temperature\":0.0,"
                + "\"top_p\":0.1,"
                + "\"num_ctx\":16384" // Injected 16k Context Window mapping here
                + "}"
                + "}";
        // Use GSON to parse the manual JSON payload to ensure validity and format
        // correctly
        Gson gson = new Gson();
        JsonObject payloadObject = gson.fromJson(rawJson, JsonObject.class);
        String jsonPayload = gson.toJson(payloadObject);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.severe("\n[ERROR] Failed to connect to local Ollama service.");
                        LOGGER.info(
                                "[HELP] Please ensure Ollama is installed and running via: ollama run qwen2.5-coder:14b");
                        LOGGER.info("[HELP] Ensure the Ollama port is accessible at: http://localhost:11434");
                        throw new RuntimeException("Ollama connection failed", throwable);
                    }
                    if (response.statusCode() != 200) {
                        LOGGER.severe("\n[ERROR] Ollama returned non-200 status code: " + response.statusCode());
                        LOGGER.severe("Response body: " + response.body());
                        throw new RuntimeException("Ollama non-200 status code: " + response.statusCode());
                    }
                    JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                    String feedback;
                    if (jsonResponse.has("message")) {
                        feedback = jsonResponse.getAsJsonObject("message").get("content").getAsString();
                    } else {
                        feedback = jsonResponse.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message").get("content").getAsString();
                    }
                    LOGGER.info("\n==================================================");
                    LOGGER.info("                AI CODE REVIEW FEEDBACK           ");
                    LOGGER.info("==================================================");
                    LOGGER.info(feedback);
                    LOGGER.info("==================================================");
                    return feedback;
                });
    }
}
