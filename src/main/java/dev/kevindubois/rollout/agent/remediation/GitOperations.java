package dev.kevindubois.rollout.agent.remediation;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deterministic git operations using JGit library.
 * This class does NOT use AI - all operations are standard library calls.
 */
@ApplicationScoped
public class GitOperations {

	public Path cloneRepository(String repoUrl, String token) throws GitAPIException, IOException {
		Path localPath = Files.createTempDirectory("k8s-agent-fix-");
		Log.infof("Cloning repository %s to %s", repoUrl, localPath);

		Git.cloneRepository()
			.setURI(repoUrl)
			.setDirectory(localPath.toFile())
			.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
			.call();

		Log.info("Successfully cloned repository");
		return localPath;
	}

	/**
	 * Fetch latest changes from origin and hard-reset the working copy to the
	 * default branch, discarding any local modifications or branches.
	 */
	public void fetchAndReset(Path repoPath, String token) throws GitAPIException, IOException {
		Log.infof("Fetching and resetting cached clone at %s", repoPath);

		try (Git git = Git.open(repoPath.toFile())) {
			String defaultBranch = git.getRepository().getBranch();

			git.checkout()
				.setName(defaultBranch)
				.setForced(true)
				.call();

			git.fetch()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
				.setRemote("origin")
				.call();

			git.reset()
				.setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
				.setRef("origin/" + defaultBranch)
				.call();

			git.branchList().call().stream()
				.map(ref -> ref.getName().replace("refs/heads/", ""))
				.filter(name -> !name.equals(defaultBranch))
				.forEach(name -> {
					try {
						git.branchDelete().setBranchNames(name).setForce(true).call();
					} catch (GitAPIException e) {
						Log.warnf("Could not delete branch %s: %s", name, e.getMessage());
					}
				});
		}

		Log.info("Cached clone is now clean and up-to-date");
	}

	public void createBranch(Path repoPath, String branchName) throws GitAPIException, IOException {
		Log.infof("Creating branch: %s", branchName);

		try (Git git = Git.open(repoPath.toFile())) {
			git.checkout()
				.setCreateBranch(true)
				.setName(branchName)
				.call();
		}

		Log.infof("Successfully created and checked out branch: %s", branchName);
	}

	public void commitAndPush(Path repoPath, String message, String token) throws GitAPIException, IOException {
		Log.info("Committing and pushing changes");

		try (Git git = Git.open(repoPath.toFile())) {
			git.add()
				.addFilepattern(".")
				.call();

			git.commit()
				.setMessage(message)
				.call();
			Log.infof("Committed changes with message: %s", message);

			String branchName = git.getRepository().getBranch();
			Log.infof("Pushing branch: %s", branchName);

			git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
				.setRemote("origin")
				.setRefSpecs(new org.eclipse.jgit.transport.RefSpec(branchName + ":" + branchName))
				.call();

			Log.info("Successfully pushed changes to remote");
		}
	}

	public void cleanup(Path repoPath) {
		try {
			if (repoPath != null && Files.exists(repoPath)) {
				deleteDirectory(repoPath.toFile());
				Log.infof("Cleaned up temporary directory: %s", repoPath);
			}
		} catch (Exception e) {
			Log.warnf("Failed to clean up directory %s: %s", repoPath, e.getMessage());
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
