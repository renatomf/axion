package dev.finaxis.core;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The owner of the schemas, on connections of its own, for tests that arrange or inspect rows as no
 * module role may. The service never connects as the owner.
 */
public final class DatabaseOwner {

    private final DataSource dataSource;
    private final JdbcClient jdbc;

    DatabaseOwner(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Runs each statement in a transaction of its own, committed when it ends. */
    public JdbcClient jdbc() {
        return jdbc;
    }

    /** Opens a session whose statements share one transaction until it commits or closes. */
    public Session session() {
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return new Session(connection);
        } catch (SQLException e) {
            throw new UncategorizedSQLException("open an owner session", null, e);
        }
    }

    /** One connection in a transaction; what was not committed is rolled back on close. */
    public static final class Session implements AutoCloseable {

        private final Connection connection;
        private final JdbcClient jdbc;

        private Session(Connection connection) {
            this.connection = connection;
            this.jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        }

        public JdbcClient jdbc() {
            return jdbc;
        }

        /** Commits, running the checks deferred to commit, and starts the next transaction. */
        public void commit() {
            try {
                connection.commit();
            } catch (SQLException e) {
                throw new UncategorizedSQLException("commit", null, e);
            }
        }

        @Override
        public void close() {
            try (connection) {
                connection.rollback();
            } catch (SQLException e) {
                throw new UncategorizedSQLException("close an owner session", null, e);
            }
        }
    }
}
