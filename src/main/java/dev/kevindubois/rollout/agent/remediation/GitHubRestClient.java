package dev.kevindubois.rollout.agent.remediation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Quarkus REST Client for GitHub API
 */
@ApplicationScoped
@RegisterRestClient(configKey = "github-api")
@Path("/repos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GitHubRestClient {

    @GET
    @Path("/{owner}/{repo}")
    GitHubRepository getRepository(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @HeaderParam("Authorization") String authorization
    );

    @POST
    @Path("/{owner}/{repo}/pulls")
    GitHubPullRequest createPullRequest(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @HeaderParam("Authorization") String authorization,
            CreatePullRequestRequest request
    );

    @POST
    @Path("/{owner}/{repo}/issues")
    GitHubIssue createIssue(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @HeaderParam("Authorization") String authorization,
            CreateIssueRequest request
    );

    @GET
    @Path("/{owner}/{repo}/git/trees/{tree_sha}")
    GitHubTree getTree(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("tree_sha") String treeSha,
            @QueryParam("recursive") String recursive,
            @HeaderParam("Authorization") String authorization
    );

    @GET
    @Path("/{owner}/{repo}/contents/{path}")
    GitHubFileContent getFileContent(
            @PathParam("owner") String owner,
            @PathParam("repo") String repo,
            @PathParam("path") String path,
            @QueryParam("ref") String ref,
            @HeaderParam("Authorization") String authorization
    );

    // DTOs
    record GitHubRepository(
            String name,
            String full_name,
            String default_branch,
            String html_url
    ) {}

    record GitHubPullRequest(
            int number,
            String html_url,
            String state,
            String title
    ) {}

    record CreatePullRequestRequest(
            String title,
            String head,
            String base,
            String body
    ) {}

    record GitHubIssue(
            int number,
            String html_url,
            String state,
            String title
    ) {}

    record CreateIssueRequest(
            String title,
            String body,
            String[] labels,
            String[] assignees
    ) {}

    record GitHubTree(
            String sha,
            String url,
            java.util.List<TreeEntry> tree,
            boolean truncated
    ) {}

    record TreeEntry(
            String path,
            String mode,
            String type,
            String sha,
            long size
    ) {}

    record GitHubFileContent(
            String name,
            String path,
            String sha,
            int size,
            String url,
            String html_url,
            String git_url,
            String download_url,
            String type,
            String content,
            String encoding
    ) {}
}
