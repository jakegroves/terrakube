package io.terrakube.api.rs.job.metrics;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.predicates.InPredicate;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.FilterExpressionCheck;
import com.yahoo.elide.core.type.Type;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JobMetric (Elide Aggregation Data Store) only allows UserCheck/FilterExpressionCheck,
 * not the OperationChecks Job itself uses ("team view job" et al. need the concrete Job
 * row to inspect workspace/project access lists). This reuses the exact same
 * MembershipService calls those OperationChecks make, applied once across every
 * workspace up front instead of once per row, and turns the result into a
 * {@code workspace_id IN (...)} predicate the aggregation query engine pushes into SQL.
 */
@Slf4j
@SecurityCheck(TeamViewJobMetric.RULE)
public class TeamViewJobMetric extends FilterExpressionCheck<JobMetric> {
    public static final String RULE = "team view job metric";

    @Autowired
    MembershipService membershipService;

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Override
    public FilterExpression getFilterExpression(Type<?> entityClass, RequestScope requestScope) {
        boolean isSuperUser = authenticatedUser.isSuperUser(requestScope.getUser());
        Map<UUID, Boolean> organizationMembershipCache = new HashMap<>();
        List<Object> allowedWorkspaceIds = new ArrayList<>();

        for (Workspace workspace : workspaceRepository.findAll()) {
            if (isSuperUser || isAuthorized(workspace, requestScope, organizationMembershipCache)) {
                allowedWorkspaceIds.add(workspace.getId().toString());
            }
        }

        Path path = getFieldPath(entityClass, requestScope, "workspaceId", "workspaceId");
        return new InPredicate(path, allowedWorkspaceIds);
    }

    private boolean isAuthorized(Workspace workspace, RequestScope requestScope, Map<UUID, Boolean> organizationMembershipCache) {
        Organization organization = workspace.getOrganization();
        if (organization != null) {
            boolean hasFullTeamAccess = organizationMembershipCache.computeIfAbsent(organization.getId(),
                    orgId -> membershipService.checkMembership(requestScope.getUser(), organization.getTeam()));
            if (hasFullTeamAccess) {
                return true;
            }
        }

        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()
                && membershipService.checkProjectMembership(requestScope.getUser(), project.getProjectAccess(), projectAccess -> true)) {
            return true;
        }

        return workspace.getAccess() != null && membershipService.checkLimitedMembership(requestScope.getUser(), workspace.getAccess());
    }
}
