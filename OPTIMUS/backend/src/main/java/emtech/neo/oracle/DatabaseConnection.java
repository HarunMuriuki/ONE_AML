package one.neo.oracle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Oracle connection: env vars (ORACLE_URL/USER/PASSWORD) win over the
 * encrypted db.* values in application.properties. Returns null on failure
 * - callers fail fast (503), never serve fake rows.
 */
@Component
public class DatabaseConnection {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);

    private final Configurations cf = new Configurations();
    private final String key = cf.getProperties().getProperty("enc.key");
    private final String initVector = cf.getProperties().getProperty("enc.initVector");
    private final String dbclass = cf.getProperties().getProperty("db.class");
    private final String ip = cf.getProperties().getProperty("db.ip");
    private final String port = cf.getProperties().getProperty("db.port");
    private final String databaseName = cf.getProperties().getProperty("db.database");
    private final String username = cf.getProperties().getProperty("db.username");
    private final String password = cf.getProperties().getProperty("db.password");

    public Connection dbConnection() {
        Connection conn = null;
        try {
            String fullUrl = env("ORACLE_URL", null);
            String uname;
            String pass;
            String url;
            if (fullUrl != null && !fullUrl.isBlank()) {
                String driver = env("ORACLE_DRIVER", "oracle.jdbc.driver.OracleDriver");
                Class.forName(driver);
                uname = env("ORACLE_USER", null);
                pass = env("ORACLE_PASSWORD", null);
                url = fullUrl;
            } else {
                String hostOverride = env("ORACLE_HOST", null);
                String driver = Encryptor.decrypt(key, initVector, dbclass);
                String host = hostOverride != null ? hostOverride : Encryptor.decrypt(key, initVector, ip);
                String portNumber = env("ORACLE_PORT", Encryptor.decrypt(key, initVector, port));
                String sid = env("ORACLE_SID", Encryptor.decrypt(key, initVector, databaseName));
                url = "jdbc:oracle:thin:@" + host + ":" + portNumber + ":" + sid;
                Class.forName(driver);
                uname = env("ORACLE_USER", Encryptor.decrypt(key, initVector, username));
                pass = env("ORACLE_PASSWORD", Encryptor.decrypt(key, initVector, password));
            }
            DriverManager.setLoginTimeout(10);
            conn = DriverManager.getConnection(url, uname, pass);
        } catch (Exception e) {
            log.error("DB Error {}", e.getMessage());
        }
        return conn;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
