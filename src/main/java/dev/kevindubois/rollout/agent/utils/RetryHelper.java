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
	private static final Pattern RETRY_PATTERN = Pattern.compile("Please retry in ([0-9.]+)s");
	
	public static <T> T executeWithRetryOnTransientErrors(Callable<T> operation, String operationName) throws Exception {
		return executeWithRetry(operation, operationName, true);
	}
	
	public static <T> T executeWithRetry(Callable<T> operation, String operationName) throws Exception {
		return executeWithRetry(operation, operationName, false);
	}
	
	private static <T> T executeWithRetry(Callable<T> operation, String operationName, boolean includeTransientErrors) throws Exception {
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
				boolean shouldRetry = is429Error(e) || (includeTransientErrors && isTransientGeminiError(e));
				
				if (!shouldRetry) {
					throw e;
				}
				
				handleRetryableError(e, operationName, attempt, includeTransientErrors);
				
				if (attempt < MAX_RETRIES) {
					currentBackoff = waitAndCalculateNextBackoff(e, operationName, currentBackoff);
				}
			}
		}
		
		// Max retries exceeded
		Log.error(MessageFormat.format("Max retries ({0}) exceeded for {1}", MAX_RETRIES, operationName));
		throw new RuntimeException(MessageFormat.format("Max retries exceeded after {0} attempts for {1}", attempt, operationName), lastException);
	}
	
	private static boolean is429Error(Exception e) {
		String message = e.getMessage();
		if (message == null) return false;
		
		String lowerMessage = message.toLowerCase();
		return lowerMessage.contains("429") ||
			lowerMessage.contains("quota") ||
			lowerMessage.contains("rate limit") ||
			lowerMessage.contains("resource_exhausted") ||
			lowerMessage.contains("exceeded your current quota");
	}
	
	private static boolean isTransientGeminiError(Exception e) {
		if (e instanceof NullPointerException) {
			String message = e.getMessage();
			if (message != null && message.contains("\"parts\" is null")) {
				return true;
			}
			
			StackTraceElement[] stackTrace = e.getStackTrace();
			if (stackTrace != null && stackTrace.length > 0) {
				for (StackTraceElement element : stackTrace) {
					if (element.getClassName().contains("GenerateContentResponseHandler")) {
						return true;
					}
				}
			}
		}
		
		String message = e.getMessage();
		if (message != null) {
			return message.contains("503") ||
				message.contains("Service Unavailable") ||
				message.contains("temporarily unavailable") ||
				message.contains("Internal Server Error");
		}
		
		return false;
	}
	
	private static void handleRetryableError(Exception e, String operationName, int attempt, boolean includeTransientErrors) {
		if (is429Error(e)) {
			Duration waitTime = extractRetryDelay(e);
			if (waitTime != null) {
				Log.warn(MessageFormat.format("Rate limit exceeded for {0}, API suggests waiting {1} seconds",
					operationName, waitTime.getSeconds()));
			} else {
				Log.warn(MessageFormat.format("Rate limit exceeded for {0} (attempt {1}/{2})",
					operationName, attempt, MAX_RETRIES));
			}
			logQuotaDetails(e);
		} else if (includeTransientErrors) {
			Log.warn(MessageFormat.format("Transient API error for {0} (attempt {1}/{2}): {3}",
				operationName, attempt, MAX_RETRIES, e.getClass().getSimpleName()));
		}
	}
	
	private static Duration waitAndCalculateNextBackoff(Exception e, String operationName, Duration currentBackoff) throws RuntimeException {
		Duration waitTime = extractRetryDelay(e);
		Duration effectiveBackoff = waitTime != null ? waitTime : currentBackoff;
		
		try {
			Log.info(MessageFormat.format("Waiting {0} seconds before retry...", effectiveBackoff.getSeconds()));
			Thread.sleep(effectiveBackoff.toMillis());
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted during retry backoff", ie);
		}
		
		Duration nextBackoff = Duration.ofMillis((long) (currentBackoff.toMillis() * BACKOFF_MULTIPLIER));
		return nextBackoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : nextBackoff;
	}
	
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


