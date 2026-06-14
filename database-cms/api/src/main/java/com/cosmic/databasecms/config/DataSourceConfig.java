package com.cosmic.databasecms.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {
    @Bean("cmsDataSource")
    @Primary
    DataSource cmsDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource source = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        source.setPoolName("cosmic-database-cms-primary");
        source.setMaximumPoolSize(6);
        return source;
    }

    @Bean
    @Primary
    JdbcTemplate cmsJdbc(@Qualifier("cmsDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean("transactionManager")
    @Primary
    PlatformTransactionManager cmsTransactionManager(@Qualifier("cmsDataSource") DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean("gameDataSource")
    DataSource gameDataSource(
            @Value("${cosmic.game-database.url}") String url,
            @Value("${cosmic.game-database.username}") String username,
            @Value("${cosmic.game-database.password}") String password) {
        HikariDataSource source = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        source.setPoolName("cosmic-database-cms-game");
        source.setMaximumPoolSize(6);
        source.setReadOnly(false);
        return source;
    }

    @Bean("gameJdbc")
    NamedParameterJdbcTemplate gameJdbc(@Qualifier("gameDataSource") DataSource source) {
        return new NamedParameterJdbcTemplate(source);
    }

    @Bean("gameTransactionManager")
    PlatformTransactionManager gameTransactionManager(@Qualifier("gameDataSource") DataSource source) {
        return new DataSourceTransactionManager(source);
    }
}
