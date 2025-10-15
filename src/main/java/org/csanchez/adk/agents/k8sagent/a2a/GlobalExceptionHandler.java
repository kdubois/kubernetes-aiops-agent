package org.csanchez.adk.agents.k8sagent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

/**
 * Global exception handler for A2A endpoints
 * Catches any exceptions that escape the controller methods
 */
@ControllerAdvice
public class GlobalExceptionHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
	
	@ExceptionHandler(Exception.class)
	public ResponseEntity<A2AResponse> handleAllExceptions(Exception e, WebRequest request) {
		logger.error("Unhandled exception in A2A controller", e);
		logger.error("Request URI: {}", request.getDescription(false));
		
		A2AResponse errorResponse = new A2AResponse();
		errorResponse.setAnalysis("Unhandled error: " + e.getMessage());
		errorResponse.setRootCause("System error: " + e.getClass().getSimpleName());
		errorResponse.setRemediation("Unable to provide remediation due to system error");
		errorResponse.setPromote(true); // Default to promote on error
		errorResponse.setConfidence(0);
		
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(errorResponse);
	}
}

