package org.openmrs.maven.plugins;


import com.atlassian.jira.rest.client.domain.Issue;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.openmrs.maven.plugins.git.GithubPrRequest;
import org.openmrs.maven.plugins.utility.Project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @goal pr
 * @requiresProject false
 */
public class PullRequest extends AbstractTask {

    /**
     * Interactive mode flag, set for 'false' allows automatic testing in batch mode,
     * as it makes all 'yes/no' prompts return 'yes'
     *
     * @parameter expression="${branch}" default-value="master"
     */
    String branch;

    /**
     * id of issue against which code is commited
     *
     * @parameter expression="${issueId}"
     */
    String issueId;

    /**
     * github username, required to push and open pull request
     *
     * @parameter expression="${username}"
     */
    String username;

    /**
     * github password, required to push and open pull request
     *
     * @parameter expression="${password}"
     */
    String password;

    private static final String COMMIT_NUM_INFO_TMPL = "There are %d commits, which will be included in your pull request. " +
            "It is recommended to squash them into one. Would you like to squash them?";

    @Override
    public void executeTask() throws MojoExecutionException, MojoFailureException {
        issueId = wizard.promptForValueIfMissing(issueId, "issue id");

        Issue issue = jira.getIssue(issueId);
        if(issue == null){
            throw new MojoExecutionException("invalid issue id");
        }

        String path = mavenProject.getBasedir().getAbsolutePath();
        Repository localRepository = gitHelper.getLocalRepository(path);
        String localBranch = null;
        try {
            localBranch = localRepository.getBranch();
        } catch (IOException e) {
            throw new MojoFailureException("Error during accessing local repository", e);
        }
        Git git = new Git(localRepository);

        if(gitHelper.checkIfUncommitedChanges(git)){
            wizard.showMessage("There are uncommitted changes. Please commit before proceeding.");
            throw new MojoExecutionException("There are uncommitted changes. Please commit before proceeding.");
        }

        gitHelper.pullRebase(this, branch, new Project(mavenProject.getModel()));

        String localRef = "refs/heads/"+localBranch;
        String upstreamRef = "refs/remotes/upstream/"+branch;
        Iterable<RevCommit> commits = gitHelper.getCommitDifferential(git, localRepository, upstreamRef, localRef);

        int size = Iterables.size(commits);
        if(size > 1){
            boolean hasToSquash = wizard.promptYesNo(String.format(COMMIT_NUM_INFO_TMPL, size));
            if(hasToSquash){
                gitHelper.squashLastCommits(git, size);
            }
        }

        List<String> commitMessages = gitHelper.getCommitDifferentialMessages(git, localRepository, upstreamRef, localRef);
        List<String> messagesToModify = new ArrayList<>();

        size = Iterables.size(commitMessages);
        for(String commit : commitMessages){
            if(!commit.startsWith(issueId)){
                messagesToModify.add(commit);
            }
        }

        if(messagesToModify.size() > 0) {
            String messageText = createRenamePrompt(issueId, messagesToModify);
            wizard.showMessage(messageText);
            boolean correctMessages = wizard.promptYesNo("Would you like them to be corrected automatically?");
            if (correctMessages) {
                gitHelper.addIssueIdIfMissing(git, issueId, size);
            }
        }

        String url = mavenProject.getScm().getUrl();
        String repoName = url.substring(url.lastIndexOf("/")+1);
        username = wizard.promptForValueIfMissing(username, "github username");
        password = wizard.promptForValueIfMissing(password, "github password");

        gitHelper.push(git, username, password);

        org.eclipse.egit.github.core.PullRequest pr = gitHelper.getPullRequestIfExists(branch, username +":"+localBranch, repoName);
        if(pr == null){
            wizard.showMessage("creating new pull request...");
            String description = wizard.promptForValueIfMissingWithDefault("You can include a short %s (optional)", null, "description", " ");
            description = "https://issues.openmrs.org/browse/"+ issueId +"\n\n"+description;
            GithubPrRequest request = new GithubPrRequest.Builder()
                    .setBase(branch)
                    .setHead(username +":"+localBranch)
                    .setUsername(username)
                    .setPassword(password)
                    .setDescription(description)
                    .setTitle(issue.getKey()+" "+issue.getSummary())
                    .setRepository(repoName)
                    .build();
            pr = gitHelper.openPullRequest(request);
            wizard.showMessage("Pull request created at "+pr.getHtmlUrl());
        } else {
            wizard.showMessage("Pull request updated at " + pr.getHtmlUrl());
        }
    }



    private String createRenamePrompt(String issueId, List<String> messagesToModify) {
        StringBuilder message = new StringBuilder("Some of your commits do not start from issue id. they should be corrected as following:\n");
        for(String messageToModify : messagesToModify) {
            message.append(messageToModify);
            message.append(" -> ");
            message.append(issueId);
            message.append(" ");
            message.append(messageToModify);
            message.append("\n");
        }
        return message.toString();
    }
}
