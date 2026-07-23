package com.rxscan.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * Two datasources against one Postgres server, two databases.
 * The engine plane carries no user identity; the consumer plane is the only place a
 * userId/phone lives (docs/rxscan-tech-design-v0_2_3.md §5). Postgres cannot do
 * cross-database foreign keys, which enforces that firewall at the engine level.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.engine")
    HikariDataSource engineDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("app.datasource.consumer")
    HikariDataSource consumerDataSource() {
        return new HikariDataSource();
    }

    @Bean
    JdbcTemplate engineJdbc(@Qualifier("engineDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    JdbcTemplate consumerJdbc(@Qualifier("consumerDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    JdbcClient consumerJdbcClient(@Qualifier("consumerDataSource") DataSource ds) {
        return JdbcClient.create(ds);
    }

    // Each database is migrated from its own migration folder at startup.
    @Bean(initMethod = "migrate")
    Flyway engineFlyway(@Qualifier("engineDataSource") DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/engine")
                .load();
    }

    @Bean(initMethod = "migrate")
    Flyway consumerFlyway(@Qualifier("consumerDataSource") DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/consumer")
                .load();
    }
}
