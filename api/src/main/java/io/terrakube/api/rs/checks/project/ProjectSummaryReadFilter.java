package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.predicates.FilterPredicate;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.FilterExpressionCheck;
import com.yahoo.elide.core.type.Type;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.project.ProjectSummary;
import lombok.AllArgsConstructor;

/** Read check for the project roll-up: exactly the projects the caller can read, see {@link ProjectVisibility}. */
@SecurityCheck(ProjectSummaryReadFilter.RULE)
@AllArgsConstructor
public class ProjectSummaryReadFilter extends FilterExpressionCheck<ProjectSummary> {

    public static final String RULE = "project summary read filter";

    private final GroupService groupService;
    private final RbacService rbacService;

    @Override
    public FilterExpression getFilterExpression(Type<?> entityClass, RequestScope requestScope) {
        return ProjectVisibility.filter(entityClass, requestScope, groupService);
    }

    @Override
    public boolean applyPredicateToObject(ProjectSummary summary, FilterPredicate predicate, RequestScope requestScope) {
        return ProjectVisibility.visible(summary.getOrganization(), summary.getProjectAccess(),
                groupService.getEffectiveGroups(requestScope.getUser()), rbacService);
    }
}
