package io.terrakube.api.rs.job.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.yahoo.elide.datastores.aggregation.QueryEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class JobMetricContextLoadsTest {

    @Autowired
    private QueryEngine queryEngine;

    @Test
    void aggregationStoreQueryEngineBeanExists() {
        assertThat(queryEngine).isNotNull();
    }
}
