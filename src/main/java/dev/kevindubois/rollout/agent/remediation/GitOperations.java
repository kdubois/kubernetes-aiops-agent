package dev.kevindubois.rollout.agent.remediation;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import io.quarkus.logging.Log;

import java.io.File;
import java.text.MessageFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Deterministic git operations using JGit library.
 * This class does NOT use AI - all operations are standard library calls.
 */
public class GitOperations {
	
	/**
	 * Clone a repository to a temporary directory
	 * @param repoUrl GitHub repository URL
	 * @param token GitHub personal access token
	 * @return Path to cloned repository
	 */
	public Path cloneRepository(String repoUrl, String token) throws GitAPIException, IOException {
		Path localPath = Files.createTempDirectory("k8s-agent-fix-");
		Log.info(MessageFormat.format("Cloning repository {0} to {1}", repoUrl, localPath));
		
		Git.cloneRepository()
			.setURI(repoUrl)
			.setDirectory(localPath.toFile())
			.setCredentialsProvider(new UsernamePasswordCredentialsProvider("git", token))
			.call();
		
		Log.info("Successfully cloned repository");
		return localPath;
	}
	
	/**
	 * Create and checkout a new branch
	 * @param repoPath Path to repository
	 * @param branchName Name of branch to create
	 */
	public void createBranch(Path repoPath, String branchName) throws GitAPIException, IOException {
		Log.info(MessageFormat.format("Creating branch: {0}", branchName));
		
		try (Git git = Git.open(repoPath.toFile())) {
			git.checkout()
				.setCreateBranch(true)
				.setName(branchName)
				.call();
		}
		
		Log.info(MessageFormat.format("Successfully created and checked out branch: {0}", branchName));
	}
	
	/**
	 * Apply file changes to the repository
	 * @param repoPath Path to repository
	 * @param fileChanges Map of file paths to new content
	 */
	public void applyChanges(Path repoPath, Map<String, String> fileChanges) throws IOException {
		Log.info(MessageFormat.format("Applying {0} file changes", fileChanges.size()));
		
		for (Map.Entry<String, String> change : fileChanges.entrySet()) {
			Path filePath = repoPath.resolve(change.getKey());
			
			// Create parent directories if they don't exist
			Files.createDirectories(filePath.getParent());
			
			// Write file content
			Files.writeString(filePath, change.getValue());
			Log.debug(MessageFormat.format("Updated file: {0}", change.getKey()));
		}
		
		Log.info("Successfully applied all changes");
	}
	
	/**
	 * Commit and push changes to remote
	 * @param repoPath Path to repository
	 * @param message Commit message
	 * @param token GitHub personal access token
	 */
	public void commitAndPush(Path repoPath, String message, String token) throws GitAPIException, IOException {
		Log.info("Committing and pushing changes");
		
		try (Git git = Git.open(repoPath.toFile())) {
			// Add all changes
			git.add()
				.addFilepattern(".")
				.call();
			
			// Commit
			git.commit()
				.setMessage(message)
				.call();
			Log.info(MessageFormat.format("Committed changes with message: {0}", message));
			
			
			// Push
			git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider("git", token))
				.call();
			
			Log.info("Successfully pushed changes to remote");
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
				Log.info(MessageFormat.format("Cleaned up temporary directory: {0}", repoPath));
			}
		} catch (Exception e) {
			Log.warn(MessageFormat.format("Failed to clean up directory {0}: {1}", repoPath, e.getMessage()));
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


