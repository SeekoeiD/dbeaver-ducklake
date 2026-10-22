/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionResult;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.output.DBCOutputWriter;
import org.jkiss.dbeaver.model.impl.DefaultServerOutputReader;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * Puts the query guard's lines ("read 45 MB from object storage ...") in the SQL editor's Output
 * panel. The SQL editor asks the data source for a {@code DBCServerOutputReader} after every query;
 * this one keeps the default behaviour (statement warnings) and appends whatever the guard queued
 * for the execution context. It never touches the connection, because the editor calls it from a
 * background job that can overlap the next query.
 */
public class QueryGuardOutputReader extends DefaultServerOutputReader {

    @Override
    public void readServerOutput(
        DBRProgressMonitor monitor,
        DBCExecutionContext context,
        DBCExecutionResult executionResult,
        DBCStatement statement,
        DBCOutputWriter output
    ) throws DBCException {
        if (executionResult != null) {
            super.readServerOutput(monitor, context, executionResult, statement, output);
        }

        for (String line : QueryGuard.drainMessages(context)) {
            output.println(null, line);
        }

        output.flush();
    }
}
