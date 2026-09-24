package com.ai.reviewer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class LocalCodeReviewerTest {

    @Test
    void testRunReviewWithNoChanges() throws Exception {
        // Arrange: create a reviewer that uses a mocked HTTP client and returns no git diff.
        HttpClient mockClient = mock(HttpClient.class);
        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mockClient));

        // Mock git diff returning empty string, so the reviewer should short-circuit.
        doReturn("").when(reviewer).getGitDiff();

        // Act: run the review workflow.
        CompletableFuture<String> resultFuture = reviewer.runReview();
        String result = resultFuture.get();

        // Assert: result indicates no changes and no HTTP call was made.
        assertEquals("No changes", result);
        verify(mockClient, never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testRunReviewWithChangesAndSuccessfulResponse() throws Exception {
        // Arrange: mock the HTTP client and a successful Ollama API response.
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(
                "{\"message\":{\"content\":\"{\\\"findings\\\":[{\\\"category\\\":\\\"Locator Robustness\\\","
                + "\\\"status\\\":\\\"PASSED\\\",\\\"file\\\":\\\"\\\",\\\"line\\\":0,\\\"problem\\\":\\\"\\\","
                + "\\\"suggestedFix\\\":\\\"\\\"}]}\"}}\n");

        CompletableFuture<HttpResponse<String>> futureResponse = CompletableFuture.completedFuture(mockResponse);
        when(mockClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn((CompletableFuture) futureResponse);

        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mockClient));

        // Stub git diff returning a small change block.
        String dummyDiff = "diff --git a/test.txt b/test.txt\n" +
                           "--- a/test.txt\n" +
                           "+++ b/test.txt\n" +
                           "@@ -1,1 +1,1 @@\n" +
                           "-old_value\n" +
                           "+new_value\n";
        doReturn(dummyDiff).when(reviewer).getGitDiff();

        // Act: execute the review and collect the result.
        CompletableFuture<String> resultFuture = reviewer.runReview();
        String result = resultFuture.get();

        // Assert: the review result is the raw structured-output JSON from the mocked Ollama response.
        assertEquals("{\"findings\":[{\"category\":\"Locator Robustness\",\"status\":\"PASSED\","
                + "\"file\":\"\",\"line\":0,\"problem\":\"\",\"suggestedFix\":\"\"}]}", result);

        // Verify the outgoing request targets the local Ollama API and uses JSON.
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).sendAsync(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        HttpRequest request = requestCaptor.getValue();
        assertEquals("POST", request.method());
        assertEquals(URI.create("http://localhost:11434/api/chat"), request.uri());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void testRunReviewWithHttpErrorResponse() throws Exception {
        // Arrange: mock a failing Ollama API response with HTTP 500.
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");

        CompletableFuture<HttpResponse<String>> futureResponse = CompletableFuture.completedFuture(mockResponse);
        when(mockClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn((CompletableFuture) futureResponse);

        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mockClient));
        doReturn("+ int port = 8080;").when(reviewer).getGitDiff();

        // Act: execute the reviewer and verify that the failure is propagated.
        CompletableFuture<String> resultFuture = reviewer.runReview();

        // Assert: an exception should be thrown because Ollama returned non-200.
        assertThrows(Exception.class, resultFuture::get);
    }

    @Test
    void runReviewRemembersExactlyTheDiffItSentToOllama() throws Exception {
        // AlreadyAppliedFindingFilter in main() must check findings against the same diff Ollama saw.
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"}}\n");
        when(mockClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn((CompletableFuture) CompletableFuture.completedFuture(mockResponse));

        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mockClient));
        // The second block has no +/- lines, so filterDiff() drops it before anything reaches Ollama.
        String rawDiff = "diff --git a/Page.java b/Page.java\n"
                + "--- a/Page.java\n"
                + "+++ b/Page.java\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-    private final String a = \"#old\";\n"
                + "+    private final String a = \"[data-test='new']\";\n"
                + "diff --git a/Mode.java b/Mode.java\n"
                + "old mode 100644\n"
                + "new mode 100755\n";
        doReturn(rawDiff).when(reviewer).getGitDiff();

        reviewer.runReview().get();

        Field field = LocalCodeReviewer.class.getDeclaredField("lastReviewedDiff");
        field.setAccessible(true);
        String remembered = (String) field.get(reviewer);
        assertEquals(com.ai.reviewer.diff.DiffFetcher.filterDiff(rawDiff), remembered);
        assertFalse(remembered.contains("Mode.java"));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).sendAsync(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        // Gson HTML-escapes ' as \u0027 on the wire, so decode the JSON and read the user message itself.
        String body = readBody(requestCaptor.getValue());
        String sentDiff = com.google.gson.JsonParser.parseString(body).getAsJsonObject()
                .getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(sentDiff.contains("private final String a = \"[data-test='new']\";"));
        assertTrue(sentDiff.contains("private final String a = \"#old\";"));
        assertFalse(sentDiff.contains("Mode.java"));
    }

    @Test
    void runReviewWithNoChangesLeavesRememberedDiffEmpty() throws Exception {
        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mock(HttpClient.class)));
        doReturn("").when(reviewer).getGitDiff();

        reviewer.runReview().get();

        Field field = LocalCodeReviewer.class.getDeclaredField("lastReviewedDiff");
        field.setAccessible(true);
        assertEquals("", field.get(reviewer));
    }

    // Drains an HttpRequest's BodyPublisher into a String so the JSON actually sent can be inspected.
    private static String readBody(HttpRequest request) throws Exception {
        StringBuilder body = new StringBuilder();
        CompletableFuture<Void> done = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                body.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get();
        return body.toString();
    }
}
