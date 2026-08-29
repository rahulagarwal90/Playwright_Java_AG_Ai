package com.ai.reviewer.ollama;

import com.ai.reviewer.ReviewFinding;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * This class only turns Ollama's JSON response into a list of ReviewFinding objects, and can
 * turn that list back into a readable text block for the terminal. It doesn't know anything
 * about HTTP, Ollama, or GitHub — it just reads and displays findings data.
 */
public class FindingParser {

    // Reads the {"findings": [...]} JSON Ollama returned and turns it into ReviewFinding objects.
    public static List<ReviewFinding> parse(String reviewJson) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (reviewJson == null || reviewJson.isBlank()) {
            return findings;
        }

        JsonObject root = new Gson().fromJson(reviewJson, JsonObject.class);
        JsonArray findingsArray = (root != null) ? root.getAsJsonArray("findings") : null;
        if (findingsArray == null) {
            return findings;
        }

        for (JsonElement element : findingsArray) {
            JsonObject obj = element.getAsJsonObject();
            ReviewFinding finding = new ReviewFinding();
            finding.category = jsonFieldAsString(obj, "category");
            finding.status = jsonFieldAsString(obj, "status");
            finding.file = jsonFieldAsString(obj, "file");
            finding.line = obj.has("line") && obj.get("line").isJsonPrimitive() ? obj.get("line").getAsInt() : 0;
            finding.problem = jsonFieldAsString(obj, "problem");
            finding.suggestedFix = jsonFieldAsString(obj, "suggestedFix");
            findings.add(finding);
        }

        return findings;
    }

    // Pulls one field out of a JSON object as a plain string, or "" if it's missing.
    private static String jsonFieldAsString(JsonObject obj, String fieldName) {
        return (obj.has(fieldName) && obj.get(fieldName).isJsonPrimitive()) ? obj.get(fieldName).getAsString() : "";
    }

    // Renders parsed findings back into the same readable block the terminal has always shown.
    public static String formatForDisplay(List<ReviewFinding> findings) {
        StringBuilder sb = new StringBuilder();
        for (ReviewFinding finding : findings) {
            sb.append(finding.category).append(": STATUS: [").append(finding.status).append("]\n");
            if ("FAILED".equalsIgnoreCase(finding.status)) {
                sb.append("File: ").append(finding.file).append("\n");
                sb.append("Line: ").append(finding.line).append("\n");
                sb.append("Problem: ").append(finding.problem).append("\n");
                sb.append("AI Suggested Fix:\n").append(finding.suggestedFix).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
