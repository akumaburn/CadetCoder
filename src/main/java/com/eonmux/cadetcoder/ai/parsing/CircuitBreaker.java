package com.eonmux.cadetcoder.ai.parsing;

import com.eonmux.cadetcoder.logging.DebugLogger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker implementation for AI parsing reliability.
 * Prevents cascading failures when AI responses are consistently failing to parse.
 */
public class CircuitBreaker {
    
    private final DebugLogger debugLogger;
    private final int failureThreshold;
    private final long timeoutMs;
    private final long retryAfterMs;
    
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    
    public CircuitBreaker(int failureThreshold, long timeoutMs, long retryAfterMs) {
        this.debugLogger = DebugLogger.getInstance();
        this.failureThreshold = failureThreshold;
        this.timeoutMs = timeoutMs;
        this.retryAfterMs = retryAfterMs;
        
        debugLogger.debug("CircuitBreaker", 
            String.format("Initialized: threshold=%d, timeout=%dms, retryAfter=%dms", 
                         failureThreshold, timeoutMs, retryAfterMs));
    }
    
    /**
     * Default circuit breaker with reasonable defaults for AI parsing.
     */
    public static CircuitBreaker defaultInstance() {
        return new CircuitBreaker(
            5,     // Fail after 5 consecutive failures
            30000, // 30 second timeout for each operation
            60000  // Wait 1 minute before retrying after circuit opens
        );
    }
    
    /**
     * Executes a parsing operation with circuit breaker protection.
     * 
     * @param operation The parsing operation to execute
     * @return The result of the operation
     * @throws CircuitBreakerException if the circuit is open
     */
    public <T> T execute(ParseOperation<T> operation) throws CircuitBreakerException {
        State currentState = checkState();
        
        switch (currentState) {
            case OPEN:
                debugLogger.warn("CircuitBreaker", "Circuit is OPEN - rejecting operation");
                throw new CircuitBreakerException("Circuit breaker is OPEN due to too many failures");
                
            case HALF_OPEN:
                debugLogger.debug("CircuitBreaker", "Circuit is HALF_OPEN - attempting operation");
                return executeWithMonitoring(operation, true);
                
            case CLOSED:
            default:
                return executeWithMonitoring(operation, false);
        }
    }
    
    private <T> T executeWithMonitoring(ParseOperation<T> operation, boolean isTestCall) {
        long startTime = System.currentTimeMillis();
        
        try {
            T result = operation.execute();
            onSuccess(isTestCall);
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            onFailure(e, duration, isTestCall);
            throw new RuntimeException("Operation failed within circuit breaker", e);
        }
    }
    
    private void onSuccess(boolean isTestCall) {
        int currentSuccesses = successCount.incrementAndGet();
        
        if (isTestCall && state.get() == State.HALF_OPEN) {
            // Test call succeeded in half-open state - close the circuit
            state.set(State.CLOSED);
            failureCount.set(0);
            debugLogger.info("CircuitBreaker", "Circuit CLOSED - test call succeeded");
        } else if (state.get() == State.CLOSED) {
            // Reset failure count on successful calls in closed state
            failureCount.set(0);
        }
        
        debugLogger.debug("CircuitBreaker", 
            String.format("Operation succeeded (total successes: %d)", currentSuccesses));
    }
    
    private void onFailure(Exception exception, long duration, boolean isTestCall) {
        int currentFailures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        debugLogger.warn("CircuitBreaker", 
            String.format("Operation failed in %dms (failure count: %d): %s", 
                         duration, currentFailures, exception.getMessage()));
        
        if (isTestCall && state.get() == State.HALF_OPEN) {
            // Test call failed in half-open state - reopen the circuit
            state.set(State.OPEN);
            debugLogger.warn("CircuitBreaker", "Circuit OPENED - test call failed");
        } else if (currentFailures >= failureThreshold && state.get() == State.CLOSED) {
            // Too many failures in closed state - open the circuit
            state.set(State.OPEN);
            debugLogger.warn("CircuitBreaker", 
                String.format("Circuit OPENED - failure threshold reached (%d failures)", currentFailures));
        }
    }
    
    private State checkState() {
        State currentState = state.get();
        
        if (currentState == State.OPEN) {
            long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
            
            if (timeSinceLastFailure >= retryAfterMs) {
                // Time to try again - move to half-open
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    debugLogger.info("CircuitBreaker", 
                        String.format("Circuit HALF_OPEN - retrying after %dms", timeSinceLastFailure));
                    return State.HALF_OPEN;
                }
            }
        }
        
        return currentState;
    }
    
    /**
     * Gets current circuit breaker statistics.
     */
    public Statistics getStatistics() {
        return new Statistics(
            state.get(),
            failureCount.get(),
            successCount.get(),
            lastFailureTime.get(),
            System.currentTimeMillis()
        );
    }
    
    /**
     * Manually resets the circuit breaker to closed state.
     * Use with caution - should only be called when you're confident the underlying issue is resolved.
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        lastFailureTime.set(0);
        debugLogger.info("CircuitBreaker", "Circuit breaker manually reset to CLOSED state");
    }
    
    /**
     * Checks if the circuit breaker is currently allowing operations.
     */
    public boolean isOperational() {
        return checkState() != State.OPEN;
    }
    
    // Interfaces and classes
    
    @FunctionalInterface
    public interface ParseOperation<T> {
        T execute() throws Exception;
    }
    
    public enum State {
        CLOSED,    // Normal operation
        OPEN,      // Blocking all operations due to failures
        HALF_OPEN  // Testing if service has recovered
    }
    
    public static class Statistics {
        private final State state;
        private final int failureCount;
        private final int successCount;
        private final long lastFailureTime;
        private final long currentTime;
        
        public Statistics(State state, int failureCount, int successCount, 
                         long lastFailureTime, long currentTime) {
            this.state = state;
            this.failureCount = failureCount;
            this.successCount = successCount;
            this.lastFailureTime = lastFailureTime;
            this.currentTime = currentTime;
        }
        
        public State getState() { return state; }
        public int getFailureCount() { return failureCount; }
        public int getSuccessCount() { return successCount; }
        public long getLastFailureTime() { return lastFailureTime; }
        
        public long getTimeSinceLastFailure() {
            return lastFailureTime > 0 ? currentTime - lastFailureTime : -1;
        }
        
        public double getSuccessRate() {
            int total = successCount + failureCount;
            return total > 0 ? (double) successCount / total : 1.0;
        }
        
        @Override
        public String toString() {
            return String.format("CircuitBreakerStats{state=%s, failures=%d, successes=%d, successRate=%.2f%%}",
                               state, failureCount, successCount, getSuccessRate() * 100);
        }
    }
    
    public static class CircuitBreakerException extends Exception {

        private static final long serialVersionUID = 1L;

        public CircuitBreakerException(String message) {
            super(message);
        }
        
        public CircuitBreakerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}