package dev.kevindubois.rollout.agent.utils;

import io.quarkus.logging.Log;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for retrying operations with exponential backoff, 
 * specifically handling Gemini API 429 rate limit errors.
 */
public class RetryHelper {
	
	private static final int MAX_RETRIES = 3;
	private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
	private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
	private static final double BACKOFF_MULTIPLIER = 2.0;
	
	// Pattern to extract retry time from error message: "Please retry in 59.955530121s"
	private static final Pattern RETRY_PATTERN = Pattern.compile("Please retry in ([0-9.]+)s");
	
	/**
	 * Execute an operation with exponential backoff retry on 429 errors
	 */
	public static <T> T executeWithRetry(Callable<T> operation, String operationName) throws Exception {
		int attempt = 0;
		Duration currentBackoff = INITIAL_BACKOFF;
		Exception lastException = null;
		
		while (attempt < MAX_RETRIES) {
			attempt++;
			
			try {
				Log.debug(MessageFormat.format("Executing {0} (attempt {1}/{2})", operationName, attempt, MAX_RETRIES));
				return operation.call();
				
			} catch (Exception e) {
				lastException = e;
				
				// Check if it's a 429 rate limit error
				if (is429Error(e)) {
					// Extract retry delay from error message if available
					Duration waitTime = extractRetryDelay(e);
					if (waitTime != null) {
						Log.warn(MessageFormat.format("Rate limit exceeded for {0}, API suggests waiting {1} seconds",
							operationName, waitTime.getSeconds()));
						currentBackoff = waitTime;
					} else {
						Log.warn(MessageFormat.format("Rate limit exceeded for {0} (attempt {1}/{2}), using exponential backoff: {3} seconds",
							operationName, attempt, MAX_RETRIES, currentBackoff.getSeconds()));
					}
					
					// Log quota details if available
					logQuotaDetails(e);
					
					if (attempt < MAX_RETRIES) {
						try {
							Log.info(MessageFormat.format("Waiting {0} seconds before retry...", currentBackoff.getSeconds()));
							Thread.sleep(currentBackoff.toMillis());
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new RuntimeException("Interrupted during retry backoff", ie);
						}
						
						// Calculate next backoff (exponential)
						currentBackoff = Duration.ofMillis((long) (currentBackoff.toMillis() * BACKOFF_MULTIPLIER));
						if (currentBackoff.compareTo(MAX_BACKOFF) > 0) {
							currentBackoff = MAX_BACKOFF;
						}
					}
				} else {
					// For non-429 errors, don't retry
					throw e;
				}
			}
		}
		
		// Max retries exceeded
		Log.error(MessageFormat.format("Max retries ({0}) exceeded for {1}", MAX_RETRIES, operationName));
		throw new RuntimeException(MessageFormat.format("Max retries exceeded after {0} attempts for {1}", attempt, operationName), lastException);
	}
	
	/**
	 * Check if the exception is a 429 rate limit error
	 */
	private static boolean is429Error(Exception e) {
		String message = e.getMessage();
		if (message == null) {
			return false;
		}
		
		// Check for common 429 error patterns (case-insensitive)
		String lowerMessage = message.toLowerCase();
		return lowerMessage.contains("429") ||
			lowerMessage.contains("quota") ||
			lowerMessage.contains("rate limit") ||
			lowerMessage.contains("resource_exhausted") ||
			lowerMessage.contains("exceeded your current quota");
	}
	
	/**
	 * Check if the exception is a transient Gemini API error that should be retried
	 */
	private static boolean isTransientGeminiError(Exception e) {
		// Check for NullPointerException from Gemini response handler
		if (e instanceof NullPointerException) {
			String message = e.getMessage();
			if (message != null && message.contains("\"parts\" is null")) {
				return true;
			}
			
			// Check stack trace for Gemini response handler
			StackTraceElement[] stackTrace = e.getStackTrace();
			if (stackTrace != null && stackTrace.length > 0) {
				for (StackTraceElement element : stackTrace) {
					if (element.getClassName().contains("GenerateContentResponseHandler")) {
						return true;
					}
				}
			}
		}
		
		// Check for other transient errors
		String message = e.getMessage();
		if (message != null) {
			return message.contains("503") ||
				message.contains("Service Unavailable") ||
				message.contains("temporarily unavailable") ||
				message.contains("Internal Server Error");
		}
		
		return false;
	}
	
	/**
	 * Execute an operation with retry on transient errors (429 and Gemini API errors)
	 */
	public static <T> T executeWithRetryOnTransientErrors(Callable<T> operation, String operationName) throws Exception {
		int attempt = 0;
		Duration currentBackoff = INITIAL_BACKOFF;
		Exception lastException = null;
		
		while (attempt < MAX_RETRIES) {
			attempt++;
			
			try {
				Log.debug(MessageFormat.format("Executing {0} (attempt {1}/{2})", operationName, attempt, MAX_RETRIES));
				return operation.call();
				
			} catch (Exception e) {
				lastException = e;
				
				// Check if it's a retryable error
				boolean shouldRetry = is429Error(e) || isTransientGeminiError(e);
				
				if (shouldRetry) {
					if (is429Error(e)) {
						// Extract retry delay from error message if available
						Duration waitTime = extractRetryDelay(e);
						if (waitTime != null) {
							Log.warn(MessageFormat.format("Rate limit exceeded for {0}, API suggests waiting {1} seconds",
								operationName, waitTime.getSeconds()));
							currentBackoff = waitTime;
						} else {
							Log.warn(MessageFormat.format("Rate limit exceeded for {0} (attempt {1}/{2}), using exponential backoff: {3} seconds",
								operationName, attempt, MAX_RETRIES, currentBackoff.getSeconds()));
						}
						logQuotaDetails(e);
					} else {
						Log.warn(MessageFormat.format("Transient Gemini API error for {0} (attempt {1}/{2}): {3}",
							operationName, attempt, MAX_RETRIES, e.getClass().getSimpleName()));
						Log.warn(MessageFormat.format("Error details: {0}", e.getMessage()));
					}
					
					if (attempt < MAX_RETRIES) {
						try {
							Log.info(MessageFormat.format("Waiting {0} seconds before retry...", currentBackoff.getSeconds()));
							Thread.sleep(currentBackoff.toMillis());
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new RuntimeException("Interrupted during retry backoff", ie);
						}
						
						// Calculate next backoff (exponential)
						currentBackoff = Duration.ofMillis((long) (currentBackoff.toMillis() * BACKOFF_MULTIPLIER));
						if (currentBackoff.compareTo(MAX_BACKOFF) > 0) {
							currentBackoff = MAX_BACKOFF;
						}
					}
				} else {
					// For non-retryable errors, don't retry
					throw e;
				}
			}
		}
		
		// Max retries exceeded
		Log.error(MessageFormat.format("Max retries ({0}) exceeded for {1}", MAX_RETRIES, operationName));
		throw new RuntimeException(MessageFormat.format("Max retries exceeded after {0} attempts for {1}", attempt, operationName), lastException);
	}
	
	/**
	 * Extract retry delay from error message
	 * Looks for patterns like "Please retry in 59.955530121s"
	 */
	private static Duration extractRetryDelay(Exception e) {
		String message = e.getMessage();
		if (message == null) {
			return null;
		}
		
		Matcher matcher = RETRY_PATTERN.matcher(message);
		if (matcher.find()) {
			try {
				double seconds = Double.parseDouble(matcher.group(1));
				// Round up to nearest second
				long roundedSeconds = (long) Math.ceil(seconds);
				Log.debug(MessageFormat.format("Extracted retry delay from API: {0} seconds", roundedSeconds));
				return Duration.ofSeconds(roundedSeconds);
			} catch (NumberFormatException nfe) {
				Log.warn(MessageFormat.format("Failed to parse retry delay: {0}", matcher.group(1)));
			}
		}
		
		return null;
	}
	
	/**
	 * Log quota details from the error message
	 */
	private static void logQuotaDetails(Exception e) {
		String message = e.getMessage();
		if (message == null) {
			return;
		}
		
		// Extract quota metric info
		// Example: "Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 10"
		if (message.contains("Quota exceeded for metric:")) {
			Pattern quotaPattern = Pattern.compile("Quota exceeded for metric: ([^,]+), limit: (\\d+)");
			Matcher matcher = quotaPattern.matcher(message);
			if (matcher.find()) {
				String quotaMetric = matcher.group(1);
				String limit = matcher.group(2);
				
				Log.warn("Quota violation details:");
				Log.warn(MessageFormat.format("  - Metric: {0}", quotaMetric));
				Log.warn(MessageFormat.format("  - Limit: {0}", limit));
				Log.warn("  - For more info: https://ai.google.dev/gemini-api/docs/rate-limits");
			}
		}
	}
}


