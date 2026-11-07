package one.neo.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/** Pooled Bolt driver; connection values from spring.neo4j.* (env-overridable). */
@Configuration
public class Neo4jConfig {

    @Value("${spring.neo4j.uri:bolt://localhost:7687}")
    private String uri;

    @Value("${spring.neo4j.authentication.username:neo4j}")
    private String username;

    @Value("${spring.neo4j.authentication.password:password123}")
    private String password;

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver() {
        Config config = Config.builder()
                .withMaxConnectionPoolSize(20)
                .withConnectionAcquisitionTimeout(10, TimeUnit.SECONDS)
                .withConnectionTimeout(10, TimeUnit.SECONDS)
                .withMaxConnectionLifetime(30, TimeUnit.MINUTES)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }
}
