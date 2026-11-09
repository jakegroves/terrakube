package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.predicates.FilterPredicate;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.FilterExpressionCheck;
import com.yahoo.elide.core.type.Type;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.project.Project;
import lombok.AllArgsConstructor;

/**
 * SQL-level read check for projects. It replaces the former in-memory {@code team view project} and
 * {@code team project access view project} checks on reads, because in-memory checks stop Elide from
 * applying explicit pagination and sorting. The rule itself lives in {@link ProjectVisibility}.
 */
@SecurityCheck(ProjectReadFilter.RULE)
@AllArgsConstructor
public class ProjectReadFilter extends FilterExpressionCheck<Project> {

    public static final String RULE = "project read filter";

    private final GroupService groupService;
    private final RbacService rbacService;

    @Override
    public FilterExpression getFilterExpression(Type<?> entityClass, RequestScope requestScope) {
        return ProjectVisibility.filter(entityClass, requestScope, groupService);
    }

    @Override
    public boolean applyPredicateToObject(Project project, FilterPredicate predicate, RequestScope requestScope) {
        // Single-resource checks cannot traverse relations hidden by Elide security.
        return ProjectVisibility.visible(project.getOrganization(), project.getProjectAccess(),
                groupService.getEffectiveGroups(requestScope.getUser()), rbacService);
    }
}
