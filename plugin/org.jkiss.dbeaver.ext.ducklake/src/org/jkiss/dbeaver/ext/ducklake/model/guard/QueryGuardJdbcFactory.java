/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCObjectSupplier;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.jdbc.exec.JDBCFactoryDefault;
import org.jkiss.dbeaver.model.impl.jdbc.exec.JDBCStatementImpl;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Where the query guard hooks into DBeaver. A JDBC data source creates every statement wrapper
 * through its {@code JDBCFactory} ({@code JDBCDataSource.createJdbcFactory}, overridden in
 * {@code DuckLakeDataSource}), and the SQL editor runs each query as a plain {@code SCRIPT}
 * statement made by {@link #createStatement}. Wrapping that one method sees every query a user runs
 * from the SQL editor, the data editor and "Execute script", without touching DBeaver's UI, with no
 * extension point that differs between 26.1 and 26.2, and only for DuckLake connections.
 *
 * <p>DBeaver has no public pre-execution hook. The alternatives were a Query Manager listener,
 * which is told about a statement but cannot stop it, and the SQL editor's internal query job,
 * which is not API. Prepared and callable statements (queries with bound parameters) are left
 * unguarded: {@code JDBCPreparedStatementImpl} has no public constructor to extend.
 */
public class QueryGuardJdbcFactory extends JDBCFactoryDefault {

    @Override
    public JDBCStatement createStatement(
        JDBCSession session,
        JDBCObjectSupplier<Statement> supplier,
        boolean disableLogging
    ) throws SQLException {
        return new GuardedStatement(session, supplier, disableLogging);
    }

    /**
     * A statement that runs the guard's pre-flight in {@link #executeStatement} and its measurement
     * in {@link #close}, after the result set is gone. Each execution gets its pre-flight once.
     */
    static final class GuardedStatement extends JDBCStatementImpl<Statement> {

        private static final Log log = Log.getLog(GuardedStatement.class);

        private QueryGuard.Token token;

        GuardedStatement(JDBCSession session, JDBCObjectSupplier<Statement> supplier, boolean disableLogging)
            throws SQLException {
            super(session, supplier, null, disableLogging);
        }

        @Override
        public boolean executeStatement() throws DBCException {
            finish();

            try {
                token = QueryGuard.beforeExecute(getConnection(), getQueryString());
            } catch (RuntimeException e) {
                // A bug in the guard must never stop the user's query.
                log.warn("Query guard failed, running the query unchecked", e);
                token = null;
            }

            return super.executeStatement();
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                finish();
            }
        }

        private void finish() {
            QueryGuard.Token done = token;
            token = null;

            if (done == null) {
                return;
            }

            try {
                QueryGuard.afterClose(done);
            } catch (RuntimeException e) {
                log.debug("Query guard measurement failed", e);
            }
        }
    }
}
