package com.ai.reviewer.github;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This class only knows about the PR's list of changed files, and how to match an AI-reported
 * file path (which might be a bare filename, or slightly off) against that real list. It
 * doesn't post comments or resolve commit SHAs — GitHubCommentPoster asks this class "what's
 * the real path for this finding?" and uses the answer.
 */
class ChangedFilePathResolver {

    private final HttpClient httpClient;

    // Stores the HttpClient used to ask GitHub which files changed in the PR.
    ChangedFilePathResolver(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // Downloads the list of files changed in the PR, so AI-reported paths can be matched against them.
    List<String> fetchChangedFiles(String repo, String prNumber, String apiBase) throws Exception {
        List<String> changedFiles = new ArrayList<>();
        Gson gson = new Gson();

        for (int page = 1; page <= 10; page++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("%s/repos/%s/pulls/%s/files?per_page=100&page=%d", apiBase, repo, prNumber, page)))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Authorization", "Bearer " + GitHubContext.token())
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

    // Matches an AI-reported file path to the PR's real path: exact match, then unique suffix, then unique basename.
    Optional<String> resolve(String rawPath, List<String> changedFiles) {
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

    // Converts a path to forward slashes and strips a leading "./" so paths compare cleanly.
    private String normalizePath(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "").trim();
    }

    // Returns just the filename part of a path, after the last slash.
    private String fileNamePart(String path) {
        int slashIndex = path.lastIndexOf('/');
        return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
    }
}
