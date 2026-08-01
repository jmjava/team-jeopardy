package com.jmjava.teamjeopardy.persist;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the SQLite DataSource after creating the parent directory.
 */
@Configuration
public class SqliteDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(SqliteDataSourceConfig.class);

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(
            DataSourceProperties properties,
            @Value("${team-jeopardy.persistence.path:./data/team-jeopardy.db}") String dbPath
    ) throws IOException {
        Path path = Path.of(dbPath).toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        log.info("SQLite question bank path: {}", path);

        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .url("jdbc:sqlite:" + path)
                .driverClassName("org.sqlite.JDBC")
                .build();
        dataSource.setMaximumPoolSize(1);
        dataSource.setConnectionInitSql("PRAGMA foreign_keys=ON");
        return dataSource;
    }
}
