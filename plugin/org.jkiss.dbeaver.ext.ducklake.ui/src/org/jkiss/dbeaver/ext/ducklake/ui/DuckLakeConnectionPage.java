/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.ui;

import org.eclipse.jface.dialogs.IDialogPage;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.generic.views.GenericConnectionPage;
import org.jkiss.dbeaver.ui.dialogs.connection.DriverPropertiesDialogPage;

/**
 * DuckLake connection page. The main page is the standard generic page
 * (Host/Port/Database/User/Password → the Postgres catalog); an extra tab
 * collects the S3 storage settings.
 */
public class DuckLakeConnectionPage extends GenericConnectionPage {

    @Nullable
    @Override
    public IDialogPage[] getDialogPages(boolean extrasOnly, boolean forceCreate) {
        if (extrasOnly) {
            return new IDialogPage[] {
                new DuckLakeConnectionPageAdvanced()
            };
        }
        return new IDialogPage[] {
            new DuckLakeConnectionPageAdvanced(), new DriverPropertiesDialogPage(this)
        };
    }
}
