package org.csanchez.adk.agents.k8sagent.remediation;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Deterministic git operations using JGit library.
 * This class does NOT use AI - all operations are standard library calls.
 */
public class GitOperations {
	
	private static final Logger logger = LoggerFactory.getLogger(GitOperations.class);
	
	/**
	 * Clone a repository to a temporary directory
	 * @param repoUrl GitHub repository URL
	 * @param token GitHub personal access token
	 * @return Path to cloned repository
	 */
	public Path cloneRepository(String repoUrl, String token) throws GitAPIException, IOException {
		Path localPath = Files.createTempDirectory("k8s-agent-fix-");
		logger.info("Cloning repository {} to {}", repoUrl, localPath);
		
		Git.cloneRepository()
			.setURI(repoUrl)
			.setDirectory(localPath.toFile())
			.setCredentialsProvider(new UsernamePasswordCredentialsProvider("git", token))
			.call();
		
		logger.info("Successfully cloned repository");
		return localPath;
	}
	
	/**
	 * Create and checkout a new branch
	 * @param repoPath Path to repository
	 * @param branchName Name of branch to create
	 */
	public void createBranch(Path repoPath, String branchName) throws GitAPIException, IOException {
		logger.info("Creating branch: {}", branchName);
		
		try (Git git = Git.open(repoPath.toFile())) {
			git.checkout()
				.setCreateBranch(true)
				.setName(branchName)
				.call();
		}
		
		logger.info("Successfully created and checked out branch: {}", branchName);
	}
	
	/**
	 * Apply file changes to the repository
	 * @param repoPath Path to repository
	 * @param fileChanges Map of file paths to new content
	 */
	public void applyChanges(Path repoPath, Map<String, String> fileChanges) throws IOException {
		logger.info("Applying {} file changes", fileChanges.size());
		
		for (Map.Entry<String, String> change : fileChanges.entrySet()) {
			Path filePath = repoPath.resolve(change.getKey());
			
			// Create parent directories if they don't exist
			Files.createDirectories(filePath.getParent());
			
			// Write file content
			Files.writeString(filePath, change.getValue());
			logger.debug("Updated file: {}", change.getKey());
		}
		
		logger.info("Successfully applied all changes");
	}
	
	/**
	 * Commit and push changes to remote
	 * @param repoPath Path to repository
	 * @param message Commit message
	 * @param token GitHub personal access token
	 */
	public void commitAndPush(Path repoPath, String message, String token) throws GitAPIException, IOException {
		logger.info("Committing and pushing changes");
		
		try (Git git = Git.open(repoPath.toFile())) {
			// Add all changes
			git.add()
				.addFilepattern(".")
				.call();
			
			// Commit
			git.commit()
				.setMessage(message)
				.call();
			
			logger.info("Committed changes with message: {}", message);
			
			// Push
			git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider("git", token))
				.call();
			
			logger.info("Successfully pushed changes to remote");
		}
	}
	
	/**
	 * Clean up temporary repository directory
	 * @param repoPath Path to repository
	 */
	public void cleanup(Path repoPath) {
		try {
			if (repoPath != null && Files.exists(repoPath)) {
				deleteDirectory(repoPath.toFile());
				logger.info("Cleaned up temporary directory: {}", repoPath);
			}
		} catch (Exception e) {
			logger.warn("Failed to clean up directory {}: {}", repoPath, e.getMessage());
		}
	}
	
	private void deleteDirectory(File directory) {
		File[] files = directory.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					deleteDirectory(file);
				} else {
					file.delete();
				}
			}
		}
		directory.delete();
	}
}


