package one.neo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NEO - minimal Oracle -> Neo4j AML pipeline service.
 * No auth / audit / reports: those are added later, on top.
 */
@SpringBootApplication
public class NeoApplication {

    public static void main(String[] args) {
        SpringApplication.run(NeoApplication.class, args);
    }
}
