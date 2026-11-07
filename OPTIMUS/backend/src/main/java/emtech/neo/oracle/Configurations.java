package one.neo.oracle;

import java.io.InputStream;
import java.util.Properties;

/** Loads application.properties from the classpath (for encrypted db.* values). */
public class Configurations {

    public Properties getProperties() {
        Properties prop = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                prop.load(input);
            } else {
                System.err.println("unable to find application.properties");
            }
        } catch (Exception e) {
            System.err.println("Error loading properties: " + e.getMessage());
        }
        return prop;
    }
}
