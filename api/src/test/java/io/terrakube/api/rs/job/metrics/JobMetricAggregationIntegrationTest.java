package io.terrakube.api.rs.job.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class JobMetricAggregationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube");

    @DynamicPropertySource
    static void registerPostgreSQLProperties(DynamicPropertyRegistry registry) {
        registry.add("io.terrakube.api.plugin.datasource.type", () -> "POSTGRESQL");
        registry.add("io.terrakube.api.plugin.datasource.hostname", postgreSQLContainer::getHost);
        registry.add("io.terrakube.api.plugin.datasource.databasePort", () -> postgreSQLContainer.getMappedPort(5432).toString());
        registry.add("io.terrakube.api.plugin.datasource.databaseName", postgreSQLContainer::getDatabaseName);
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", postgreSQLContainer::getUsername);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", postgreSQLContainer::getPassword);
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "jobMetricAggregationIT");
    }

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private JobRepository jobRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void avgQueueWaitAndP95DurationMatchHandComputedValues() {
        Organization organization = new Organization();
        organization.setName("metrics-test-org-" + System.nanoTime());
        organization = organizationRepository.save(organization);

        Workspace workspace = new Workspace();
        workspace.setName("metrics-test-ws");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.5.0");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.save(workspace);

        // Job 1: queued for 10s, then ran for 100s total, plan-only.
        Instant base = Instant.parse("2026-07-15T00:00:00Z");
        Job job1 = new Job();
        job1.setOrganization(organization);
        job1.setWorkspace(workspace);
        job1.setStatus(JobStatus.completed);
        job1.setVia("UI");
        job1.setPlanOnly(true);
        job1.setQueuedAt(Date.from(base));
        job1.setStartedAt(Date.from(base.plusSeconds(10)));
        job1.setFinishedAt(Date.from(base.plusSeconds(100)));
        jobRepository.save(job1);

        // Job 2: queued for 30s, then ran for 200s total, full apply.
        Job job2 = new Job();
        job2.setOrganization(organization);
        job2.setWorkspace(workspace);
        job2.setStatus(JobStatus.completed);
        job2.setVia("UI");
        job2.setPlanOnly(false);
        job2.setQueuedAt(Date.from(base));
        job2.setStartedAt(Date.from(base.plusSeconds(30)));
        job2.setFinishedAt(Date.from(base.plusSeconds(200)));
        jobRepository.save(job2);

        entityManager.flush();

        // Postgres returns AVG(...)/PERCENTILE_CONT(...) over a double-precision expression
        // as a java.math.BigDecimal via JDBC, not a Double — convert rather than cast.
        Number avgQueueWaitSeconds = (Number) entityManager.createNativeQuery(
                "SELECT AVG(EXTRACT(EPOCH FROM (started_at - queued_at))) FROM job WHERE organization_id = :orgId")
                .setParameter("orgId", organization.getId().toString())
                .getSingleResult();

        assertThat(avgQueueWaitSeconds.doubleValue()).isCloseTo(20.0, within(0.01)); // (10 + 30) / 2

        Number p95DurationSeconds = (Number) entityManager.createNativeQuery(
                "SELECT PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (finished_at - created_date))) "
                        + "FROM job WHERE organization_id = :orgId")
                .setParameter("orgId", organization.getId().toString())
                .getSingleResult();

        assertThat(p95DurationSeconds).isNotNull();

        @SuppressWarnings("unchecked")
        List<Object[]> planOnlyCounts = entityManager.createNativeQuery(
                "SELECT plan_only, COUNT(*) FROM job WHERE organization_id = :orgId GROUP BY plan_only")
                .setParameter("orgId", organization.getId().toString())
                .getResultList();

        assertThat(planOnlyCounts).hasSize(2);
    }
}
