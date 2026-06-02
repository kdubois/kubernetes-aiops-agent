package dev.kevindubois.rollout.agent.remediation;

import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches local git clones so that remediation retries reuse the same
 * working copy instead of cloning from scratch every time.
 */
@ApplicationScoped
public class RepoCloneCache {

    private final ConcurrentHashMap<String, Path> cache = new ConcurrentHashMap<>();

    @Inject
    GitOperations gitOps;

    /**
     * Return a local clone for the given repo URL, cloning only on the first
     * call.  Subsequent calls fetch from origin and hard-reset to the default
     * branch so the working copy is clean and up-to-date.
     */
    public Path getOrClone(String repoUrl, String token) throws Exception {
        Path existing = cache.get(repoUrl);

        if (existing != null && existing.toFile().exists()) {
            Log.info(MessageFormat.format("Reusing cached clone for {0}", repoUrl));
            gitOps.fetchAndReset(existing, token);
            return existing;
        }

        Log.info(MessageFormat.format("First clone for {0}", repoUrl));
        Path cloned = gitOps.cloneRepository(repoUrl, token);
        cache.put(repoUrl, cloned);
        return cloned;
    }

    @PreDestroy
    void cleanup() {
        cache.forEach((url, path) -> {
            Log.info(MessageFormat.format("Cleaning up cached clone: {0}", path));
            gitOps.cleanup(path);
        });
        cache.clear();
    }
}
