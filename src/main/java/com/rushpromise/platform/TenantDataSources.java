package com.rushpromise.platform;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Configuration(proxyBeanMethods = false)
class TenantDataSources {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource platformConnectionPool(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("platformConnectionPool") HikariDataSource pool) {
        // Hikari rolls back pending work, then resets dirty connection schemas before returning them to this default.
        pool.setSchema(TenantContext.LEGACY);
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return selectSchema(pool.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return selectSchema(pool.getConnection(username, password));
            }

            @Override
            public <T> T unwrap(Class<T> type) throws SQLException {
                return type.isInstance(this) ? type.cast(this) : pool.unwrap(type);
            }

            @Override
            public boolean isWrapperFor(Class<?> type) throws SQLException {
                return type.isInstance(this) || pool.isWrapperFor(type);
            }
        };
    }

    private Connection selectSchema(Connection connection) throws SQLException {
        try {
            String schema = TenantContext.schema();
            connection.setSchema(schema);
            if (!TenantContext.legacy() && !schema.equals(connection.getSchema())) {
                throw new SQLException("Tenant schema is unavailable");
            }
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); }
            catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
}
