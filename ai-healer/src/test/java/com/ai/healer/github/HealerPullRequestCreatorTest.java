package com.ai.healer.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// Exercises the label flow through the package-private overloads (openPullRequest/
// ensureHealLabelExists/addHealLabel) with an explicit repo/apiBase/token, the same way
// HealerGitHubConfigTest exercises resolveToken(String) directly - HealerGitHubConfig.repository()/
// apiBase()/token() read real System.getenv() values this test suite has no library to mock, so
// going through the public createPullRequest(...) entry point would depend on whatever GitHub env
// vars happen to be set in the JVM running the tests.
public class HealerPullRequestCreatorTest {

    private static final String REPO = "acme/widgets";
    private static final String API_BASE = "https://api.github.com";
    private static final String TOKEN = "ghs_faketoken";

    @Test
    void createsTheLabelWhenItDoesNotYetExistThenAppliesItToTheNewPullRequest() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HealerPullRequestCreator creator = new HealerPullRequestCreator(mockClient);

        HttpResponse<String> pullRequestResponse = mock(HttpResponse.class);
        when(pullRequestResponse.statusCode()).thenReturn(201);
        when(pullRequestResponse.body()).thenReturn(
                "{\"number\": 76, \"html_url\": \"https://github.com/acme/widgets/pull/76\"}");

        HttpResponse<String> labelLookupResponse = mock(HttpResponse.class);
        when(labelLookupResponse.statusCode()).thenReturn(404);
        when(labelLookupResponse.body()).thenReturn("{\"message\": \"Not Found\"}");

        HttpResponse<String> labelCreateResponse = mock(HttpResponse.class);
        when(labelCreateResponse.statusCode()).thenReturn(201);

        HttpResponse<String> addLabelResponse = mock(HttpResponse.class);
        when(addLabelResponse.statusCode()).thenReturn(200);

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(pullRequestResponse, labelLookupResponse, labelCreateResponse, addLabelResponse);

        HealerPullRequestCreator.PullRequest pullRequest = creator.openPullRequest(
                REPO, API_BASE, TOKEN, "heal/cart-feature-20260101-000000", Path.of("cart.feature"), "summary text");
        creator.ensureHealLabelExists(REPO, API_BASE, TOKEN);
        creator.addHealLabel(REPO, API_BASE, TOKEN, pullRequest.number());

        assertEquals(76, pullRequest.number());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient, times(4)).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        var requests = requestCaptor.getAllValues();

        // Request 1: open the PR.
        assertEquals("https://api.github.com/repos/acme/widgets/pulls", requests.get(0).uri().toString());

        // Request 2: check whether the label already exists.
        assertEquals("https://api.github.com/repos/acme/widgets/labels/ai-healer-generated",
                requests.get(1).uri().toString());
        assertEquals("GET", requests.get(1).method());

        // Request 3: the label was missing (404), so it gets created.
        assertEquals("https://api.github.com/repos/acme/widgets/labels", requests.get(2).uri().toString());
        assertEquals("POST", requests.get(2).method());

        // Request 4: the label is applied to the PR that was just opened (issue number == PR number).
        assertEquals("https://api.github.com/repos/acme/widgets/issues/76/labels", requests.get(3).uri().toString());
        assertEquals("POST", requests.get(3).method());
    }

    @Test
    void skipsCreatingTheLabelWhenItAlreadyExistsButStillAppliesItToThePullRequest() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HealerPullRequestCreator creator = new HealerPullRequestCreator(mockClient);

        HttpResponse<String> pullRequestResponse = mock(HttpResponse.class);
        when(pullRequestResponse.statusCode()).thenReturn(201);
        when(pullRequestResponse.body()).thenReturn(
                "{\"number\": 90, \"html_url\": \"https://github.com/acme/widgets/pull/90\"}");

        HttpResponse<String> labelLookupResponse = mock(HttpResponse.class);
        when(labelLookupResponse.statusCode()).thenReturn(200);

        HttpResponse<String> addLabelResponse = mock(HttpResponse.class);
        when(addLabelResponse.statusCode()).thenReturn(200);

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(pullRequestResponse, labelLookupResponse, addLabelResponse);

        HealerPullRequestCreator.PullRequest pullRequest = creator.openPullRequest(
                REPO, API_BASE, TOKEN, "heal/cart-feature-20260101-000000", Path.of("cart.feature"), "summary text");
        creator.ensureHealLabelExists(REPO, API_BASE, TOKEN);
        creator.addHealLabel(REPO, API_BASE, TOKEN, pullRequest.number());

        // No label-creation POST to /labels this time - only open PR, lookup, and add-to-issue.
        verify(mockClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // The real bug this test guards against: a PR was genuinely created on GitHub (openPullRequest
    // succeeded, 201 + a real PR number/URL), but the run report showed prCreated: false because a
    // labeling failure afterward propagated as an exception and made the whole createPullRequest()
    // call look like it failed. createPullRequest() must still return the PR - with
    // labelApplied: false - rather than throwing.
    @Test
    void createPullRequestStillReturnsThePrWhenLabelingFailsAfterwards() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HealerPullRequestCreator creator = new HealerPullRequestCreator(mockClient);

        HttpResponse<String> pullRequestResponse = mock(HttpResponse.class);
        when(pullRequestResponse.statusCode()).thenReturn(201);
        when(pullRequestResponse.body()).thenReturn(
                "{\"number\": 101, \"html_url\": \"https://github.com/acme/widgets/pull/101\"}");

        HttpResponse<String> labelLookupFailure = mock(HttpResponse.class);
        when(labelLookupFailure.statusCode()).thenReturn(500);
        when(labelLookupFailure.body()).thenReturn("{\"message\": \"Internal Server Error\"}");

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(pullRequestResponse, labelLookupFailure);

        HealerPullRequestCreator.PullRequest pullRequest = creator.createPullRequest(
                REPO, API_BASE, TOKEN, "heal/cart-feature-20260101-000000", Path.of("cart.feature"), "summary text");

        assertEquals(101, pullRequest.number());
        assertEquals("https://github.com/acme/widgets/pull/101", pullRequest.htmlUrl());
        assertFalse(pullRequest.labelApplied(), "labeling failed and should be reported as such");
    }

    @Test
    void addHealLabelSendsTheExpectedLabelNameInTheRequestBody() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HealerPullRequestCreator creator = new HealerPullRequestCreator(mockClient);

        HttpResponse<String> addLabelResponse = mock(HttpResponse.class);
        when(addLabelResponse.statusCode()).thenReturn(200);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(addLabelResponse);

        creator.addHealLabel(REPO, API_BASE, TOKEN, 42);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();

        assertEquals("https://api.github.com/repos/acme/widgets/issues/42/labels", request.uri().toString());
        assertEquals("POST", request.method());
        String body = bodyPublisherToString(request);
        assertTrue(body.contains("\"ai-healer-generated\""), "request body should list the heal label: " + body);
    }

    private static String bodyPublisherToString(HttpRequest request) throws Exception {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private final StringBuilder builder = new StringBuilder();

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                builder.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(builder.toString());
            }
        });
        return future.get();
    }
}
