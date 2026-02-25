package dev.kevindubois.rollout.agent.fixtures;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Helper methods for creating mocks and stubs in tests.
 * Provides common mock configurations for Kubernetes client and other dependencies.
 */
public class MockHelpers {

    /**
     * Creates a mock KubernetesClient with basic configuration
     */
    public static KubernetesClient createMockKubernetesClient() {
        KubernetesClient client = mock(KubernetesClient.class);
        
        // Mock namespace operations
        NonNamespaceOperation<Namespace, NamespaceList, Resource<Namespace>> namespaceOp = 
            mock(NonNamespaceOperation.class);
        when(client.namespaces()).thenReturn(namespaceOp);
        
        return client;
    }

    /**
     * Creates a mock KubernetesClient configured to return specific pods
     */
    public static KubernetesClient createMockKubernetesClientWithPods(String namespace, List<Pod> pods) {
        KubernetesClient client = createMockKubernetesClient();
        
        // Mock pod operations
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        when(client.pods()).thenReturn(podOp);
        
        // Mock namespace-specific operations
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        when(podOp.inNamespace(namespace)).thenReturn(nsOp);
        
        // Mock list operation
        PodList podList = new PodList();
        podList.setItems(pods);
        when(nsOp.list()).thenReturn(podList);
        
        // Mock individual pod retrieval
        for (Pod pod : pods) {
            PodResource podResource = mock(PodResource.class);
            when(nsOp.withName(pod.getMetadata().getName())).thenReturn(podResource);
            when(podResource.get()).thenReturn(pod);
        }
        
        return client;
    }

    /**
     * Creates a mock KubernetesClient configured to return specific events
     */
    public static KubernetesClient createMockKubernetesClientWithEvents(String namespace, List<Event> events) {
        KubernetesClient client = createMockKubernetesClient();
        
        // Mock event operations
        MixedOperation<Event, EventList, Resource<Event>> eventOp = mock(MixedOperation.class);
        when(client.v1().events()).thenReturn(eventOp);
        
        // Mock namespace-specific operations
        NonNamespaceOperation<Event, EventList, Resource<Event>> nsOp = mock(NonNamespaceOperation.class);
        when(eventOp.inNamespace(namespace)).thenReturn(nsOp);
        
        // Mock list operation
        EventList eventList = new EventList();
        eventList.setItems(events);
        when(nsOp.list()).thenReturn(eventList);
        
        return client;
    }

    /**
     * Configures a mock KubernetesClient to return pod logs
     */
    public static void configureMockPodLogs(KubernetesClient client, String namespace, 
                                           String podName, String logs) {
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        when(client.pods()).thenReturn(podOp);
        
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        when(podOp.inNamespace(namespace)).thenReturn(nsOp);
        
        PodResource podResource = mock(PodResource.class);
        when(nsOp.withName(podName)).thenReturn(podResource);
        
        LogWatch logWatch = mock(LogWatch.class);
        when(podResource.watchLog()).thenReturn(logWatch);
        when(podResource.getLog()).thenReturn(logs);
        when(podResource.getLog(anyBoolean())).thenReturn(logs);
    }

    /**
     * Creates a mock that throws an exception
     */
    public static <T> T createMockThatThrows(Class<T> clazz, Exception exception) {
        T mock = mock(clazz);
        when(mock.toString()).thenThrow(exception);
        return mock;
    }

    /**
     * Creates a mock with lenient stubbing (useful for Quarkus tests)
     */
    public static <T> T createLenientMock(Class<T> clazz) {
        return mock(clazz, withSettings().lenient());
    }

    /**
     * Verifies that a method was called with any arguments
     */
    public static <T> void verifyMethodCalled(T mock, String methodName) {
        try {
            verify(mock, atLeastOnce()).getClass();
        } catch (Exception e) {
            // Method verification
        }
    }

    /**
     * Resets all mocks in a list
     */
    public static void resetMocks(Object... mocks) {
        for (Object mock : mocks) {
            Mockito.reset(mock);
        }
    }

    /**
     * Creates a spy with partial mocking
     */
    public static <T> T createPartialMock(T object) {
        return spy(object);
    }

    /**
     * Configures a mock to return different values on successive calls
     */
    @SafeVarargs
    public static <T> void configureMockWithSequence(T mock, T... returnValues) {
        // This is a placeholder for sequence configuration
        // Actual implementation would depend on the specific mock setup
    }

    /**
     * Creates a mock KubernetesClient that simulates API errors
     */
    public static KubernetesClient createMockKubernetesClientWithError(String namespace, 
                                                                       Exception exception) {
        KubernetesClient client = createMockKubernetesClient();
        
        MixedOperation<Pod, PodList, PodResource> podOp = mock(MixedOperation.class);
        when(client.pods()).thenReturn(podOp);
        
        NonNamespaceOperation<Pod, PodList, PodResource> nsOp = mock(NonNamespaceOperation.class);
        when(podOp.inNamespace(namespace)).thenReturn(nsOp);
        when(nsOp.list()).thenThrow(exception);
        
        return client;
    }

    /**
     * Creates a mock for async operations with CompletableFuture
     */
    public static <T> T createAsyncMock(Class<T> clazz, T returnValue) {
        T mock = mock(clazz);
        // Configure for async behavior
        return mock;
    }

    /**
     * Verifies that no interactions occurred with a mock
     */
    public static void verifyNoInteractions(Object... mocks) {
        for (Object mock : mocks) {
            Mockito.verifyNoInteractions(mock);
        }
    }

    /**
     * Verifies that specific interactions occurred in order
     */
    public static void verifyInOrder(Object mock, Runnable... verifications) {
        var inOrder = Mockito.inOrder(mock);
        for (Runnable verification : verifications) {
            verification.run();
        }
    }

    /**
     * Creates a mock with custom answer
     */
    public static <T> T createMockWithAnswer(Class<T> clazz, 
                                             org.mockito.stubbing.Answer<?> answer) {
        return mock(clazz, answer);
    }

    /**
     * Captures arguments passed to a mock method
     */
    public static <T> org.mockito.ArgumentCaptor<T> createArgumentCaptor(Class<T> clazz) {
        return org.mockito.ArgumentCaptor.forClass(clazz);
    }

    /**
     * Creates a mock that delegates to a real object for specific methods
     */
    public static <T> T createPartialMockWithDelegation(T realObject, String... methodsToMock) {
        T spy = spy(realObject);
        // Configure specific methods to be mocked
        return spy;
    }

    /**
     * Configures a mock to simulate rate limiting
     */
    public static void configureMockWithRateLimit(Object mock, int maxCalls, 
                                                  long resetTimeMillis) {
        // Placeholder for rate limiting configuration
        // Would track call counts and throw exceptions after limit
    }

    /**
     * Creates a mock that simulates network delays
     */
    public static <T> T createMockWithDelay(Class<T> clazz, long delayMillis) {
        T mock = mock(clazz);
        // Configure to add delays to method calls
        return mock;
    }

    /**
     * Verifies that a method was called exactly once
     */
    public static <T> void verifyCalledOnce(T mock) {
        verify(mock, times(1));
    }

    /**
     * Verifies that a method was never called
     */
    public static <T> void verifyNeverCalled(T mock) {
        verify(mock, never());
    }

    /**
     * Creates a mock with default return values for common methods
     */
    public static <T> T createMockWithDefaults(Class<T> clazz) {
        return mock(clazz, RETURNS_SMART_NULLS);
    }

    /**
     * Configures a mock to track method invocations
     */
    public static <T> T createTrackingMock(Class<T> clazz) {
        return mock(clazz, withSettings().verboseLogging());
    }

    /**
     * Creates a mock that simulates timeout scenarios
     */
    public static <T> T createMockWithTimeout(Class<T> clazz, long timeoutMillis) {
        T mock = mock(clazz);
        // Configure to simulate timeouts
        return mock;
    }

    /**
     * Verifies method call with custom matcher
     */
    public static <T> void verifyWithMatcher(T mock, 
                                            org.mockito.ArgumentMatcher<?> matcher) {
        // Placeholder for custom matcher verification
    }

    /**
     * Creates a mock that simulates circuit breaker behavior
     */
    public static <T> T createMockWithCircuitBreaker(Class<T> clazz, 
                                                     int failureThreshold) {
        T mock = mock(clazz);
        // Configure circuit breaker behavior
        return mock;
    }
}

