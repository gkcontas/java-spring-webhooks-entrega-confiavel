package com.gkcontas.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApplicationContextIT extends IntegrationTestBase {

    @Test
    void flywayAppliedTheSchema() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isPositive();
    }
}
