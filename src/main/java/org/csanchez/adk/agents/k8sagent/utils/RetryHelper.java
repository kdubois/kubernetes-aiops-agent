package org.csanchez.adk.agents.k8sagent.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for retrying operations with exponential backoff, 
 * specifically handling Gemini API 429 rate limit errors.
 */
public class RetryHelper {
	
	private static final Logger logger = LoggerFactory.getLogger(RetryHelper.class);
	
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
				logger.debug("Executing {} (attempt {}/{})", operationName, attempt, MAX_RETRIES);
				return operation.call();
				
			} catch (Exception e) {
				lastException = e;
				
				// Check if it's a 429 rate limit error
				if (is429Error(e)) {
					// Extract retry delay from error message if available
					Duration waitTime = extractRetryDelay(e);
					if (waitTime != null) {
						logger.warn("Rate limit exceeded for {}, API suggests waiting {} seconds", 
							operationName, waitTime.getSeconds());
						currentBackoff = waitTime;
					} else {
						logger.warn("Rate limit exceeded for {} (attempt {}/{}), using exponential backoff: {} seconds", 
							operationName, attempt, MAX_RETRIES, currentBackoff.getSeconds());
					}
					
					// Log quota details if available
					logQuotaDetails(e);
					
					if (attempt < MAX_RETRIES) {
						try {
							logger.info("Waiting {} seconds before retry...", currentBackoff.getSeconds());
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
		logger.error("Max retries ({}) exceeded for {}", MAX_RETRIES, operationName);
		throw new RuntimeException("Max retries exceeded after " + attempt + " attempts for " + operationName, lastException);
	}
	
	/**
	 * Check if the exception is a 429 rate limit error
	 */
	private static boolean is429Error(Exception e) {
		String message = e.getMessage();
		if (message == null) {
			return false;
		}
		
		// Check for common 429 error patterns
		return message.contains("429") || 
			message.contains("quota") ||
			message.contains("rate limit") ||
			message.contains("RESOURCE_EXHAUSTED") ||
			message.contains("exceeded your current quota");
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
				logger.debug("Extracted retry delay from API: {} seconds", roundedSeconds);
				return Duration.ofSeconds(roundedSeconds);
			} catch (NumberFormatException nfe) {
				logger.warn("Failed to parse retry delay: {}", matcher.group(1));
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
				
				logger.warn("Quota violation details:");
				logger.warn("  - Metric: {}", quotaMetric);
				logger.warn("  - Limit: {}", limit);
				logger.warn("  - For more info: https://ai.google.dev/gemini-api/docs/rate-limits");
			}
		}
	}
}

