package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@AllArgsConstructor
@Slf4j
@Service
public class PrCommentService {

    private static final int MAX_COMMENT_LENGTH = 60000;
    private static final Set<VcsType> PR_COMMENT_SUPPORTED_VCS = EnumSet.of(VcsType.GITHUB, VcsType.GITLAB, VcsType.BITBUCKET);
    private static final Pattern PLAN_SUMMARY_PATTERN = Pattern.compile(
            "(Plan: \\d+ to add, \\d+ to change, \\d+ to destroy\\.|No changes\\. Your infrastructure matches the configuration\\.)");
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "[\\u001b\\u009b][\\[()#;?]*(?:[0-9]{1,4}(?:;[0-9]{0,4})*)?[0-9A-ORZcf-nq-uy=><~]");

    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    BitBucketWebhookService bitBucketWebhookService;
    JobRepository jobRepository;
    StorageTypeService storageTypeService;
    StreamingService streamingService;

    public void postPlanResult(Job job) {
        if (job.getPrNumber() == null || job.getPrNumber() == 0) return;

        String planOutput = fetchStepOutputText(job);
        String markdownComment = formatPlanComment(job, planOutput);

        String commentId = attemptPostComment(job, markdownComment);
        if (commentId != null) {
            job.setPrCommentId(commentId);
        }
        jobRepository.save(job);
    }

    public void postApplyResult(Job job) {
        if (job.getPrNumber() == null || job.getPrNumber() == 0) return;

        String output = fetchStepOutputText(job);
        String markdownComment = formatApplyComment(job, output);
        attemptPostComment(job, markdownComment);
        jobRepository.save(job);
    }

    private String attemptPostComment(Job job, String markdownComment) {
        VcsType vcsType = job.getWorkspace().getVcs().getVcsType();
        try {
            String commentId = postComment(job, markdownComment);
            if (commentId != null) {
                job.setPrCommentError(null);
            } else if (PR_COMMENT_SUPPORTED_VCS.contains(vcsType)) {
                job.setPrCommentError(buildFailureMessage(job));
            }
            return commentId;
        } catch (Exception e) {
            log.error("Error posting PR comment for job {}: {}", job.getId(), e.getMessage());
            if (PR_COMMENT_SUPPORTED_VCS.contains(vcsType)) {
                job.setPrCommentError(buildFailureMessage(job));
            }
            return null;
        }
    }

    private String buildFailureMessage(Job job) {
        return "Failed to post comment on pull request #" + job.getPrNumber()
                + ". Verify the VCS connection has write access to pull requests.";
    }

    /**
     * job.getTerraformPlan() is a storage pointer to the binary .tfplan file, and
     * job.getOutput() is just append-only step-completion markers - neither holds the
     * human-readable console text. The real diff/summary text lives in the last step's
     * console output, the same place the job details UI reads it from.
     */
    private String fetchStepOutputText(Job job) {
        Step step = job.getStep() == null ? null : job.getStep().stream()
                .max(Comparator.comparingInt(Step::getStepNumber))
                .orElse(null);
        if (step == null) {
            return null;
        }

        try {
            String stepId = step.getId().toString();
            String liveLogs = streamingService.getCurrentLogs(stepId);
            if (liveLogs != null && !liveLogs.isEmpty()) {
                return stripAnsi(liveLogs);
            }

            byte[] storedOutput = storageTypeService.getStepOutput(
                    job.getOrganization().getId().toString(), String.valueOf(job.getId()), stepId);
            if (storedOutput == null || storedOutput.length == 0) {
                return null;
            }
            return stripAnsi(new String(storedOutput, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error fetching step output for job {}: {}", job.getId(), e.getMessage());
            return null;
        }
    }

    private String stripAnsi(String text) {
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    public void postApplyDisabledNotice(Workspace workspace, Integer prNumber) {
        if (prNumber == null || prNumber == 0) return;

        Job transientJob = new Job();
        transientJob.setWorkspace(workspace);
        transientJob.setPrNumber(prNumber);

        String markdown = "## Terrakube Apply\n\n" +
                "⚠️ Apply via PR comment is not enabled for this workspace.\n\n" +
                "Ask a workspace admin to enable **Allow Apply via PR Comment** in the webhook settings, " +
                "or apply this plan from the Terrakube UI.\n";

        postComment(transientJob, markdown);
    }

    private String postComment(Job job, String markdownComment) {
        String commentId = null;
        switch (job.getWorkspace().getVcs().getVcsType()) {
            case GITHUB:
                commentId = gitHubWebhookService.postPrComment(job, markdownComment);
                break;
            case GITLAB:
                commentId = gitLabWebhookService.postMergeRequestNote(job, markdownComment);
                break;
            case BITBUCKET:
                commentId = bitBucketWebhookService.postPrComment(job, markdownComment);
                break;
            default:
                break;
        }
        return commentId;
    }

    private String statusIcon(JobStatus status) {
        switch (status) {
            case completed:
                return "✅";
            case failed:
                return "❌";
            default:
                return "⚠️";
        }
    }

    private String formatPlanComment(Job job, String planOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Terrakube Plan Output\n\n");
        sb.append("**Workspace:** ").append(job.getWorkspace().getName()).append("\n");
        sb.append("**Status:** ").append(job.getStatus()).append("\n");
        sb.append("**Job:** #").append(job.getId()).append("\n\n");

        String icon = statusIcon(job.getStatus());

        if (planOutput != null && !planOutput.isEmpty()) {
            Matcher summaryMatcher = PLAN_SUMMARY_PATTERN.matcher(planOutput);
            if (summaryMatcher.find()) {
                sb.append(icon).append(" ").append(summaryMatcher.group(1)).append("\n\n");
            }

            String content = planOutput;
            if (content.length() > MAX_COMMENT_LENGTH) {
                content = content.substring(0, MAX_COMMENT_LENGTH)
                        + "\n\n... (output truncated, see full output in Terrakube UI)";
            }
            sb.append("<details><summary>Show Plan</summary>\n\n");
            sb.append("```diff\n");
            sb.append(content);
            sb.append("\n```\n\n</details>\n\n");
        } else if (job.getStatus() == JobStatus.completed) {
            sb.append(icon).append(" No changes detected.\n\n");
        } else {
            sb.append(icon).append(" Plan failed. Check the Terrakube UI for details.\n\n");
        }

        sb.append("---\n");
        sb.append("To apply this plan, comment: `terrakube apply`\n");
        sb.append("To re-plan, comment: `terrakube plan`\n");

        return sb.toString();
    }

    private String formatApplyComment(Job job, String output) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Terrakube Apply Output\n\n");
        sb.append("**Workspace:** ").append(job.getWorkspace().getName()).append("\n");
        sb.append("**Status:** ").append(job.getStatus()).append("\n");
        sb.append("**Job:** #").append(job.getId()).append("\n\n");

        String icon = statusIcon(job.getStatus());
        String summary = job.getStatus() == JobStatus.completed ? "Apply complete" : "Apply failed";
        sb.append(icon).append(" ").append(summary).append("\n\n");

        if (output != null && !output.isEmpty()) {
            String content = output;
            if (content.length() > MAX_COMMENT_LENGTH) {
                content = content.substring(0, MAX_COMMENT_LENGTH)
                        + "\n\n... (output truncated, see full output in Terrakube UI)";
            }
            sb.append("<details><summary>Show Apply Output</summary>\n\n");
            sb.append("```\n");
            sb.append(content);
            sb.append("\n```\n\n</details>\n");
        }

        return sb.toString();
    }
}
