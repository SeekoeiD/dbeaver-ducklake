/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.ducklake.model.DuckLakeConstants;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.connection.ConnectionPageAbstract;
import org.jkiss.utils.CommonUtils;

/**
 * Extra "DuckLake storage" tab: S3 endpoint/credentials + DATA_PATH + catalog alias, all stored as
 * connection provider properties (read back when building the ATTACH).
 *
 * <p>Nothing on this tab is required. Leaving the whole "DuckLake catalog" group blank is a normal
 * setup: the plugin then attaches every catalog the Postgres role can read and makes the first one
 * active. The fields only override that, which is what the tooltips say.
 */
public class DuckLakeConnectionPageAdvanced extends ConnectionPageAbstract {

    private Text endpointText;
    private Text keyText;
    private Text secretText;
    private Text regionText;
    private Text urlStyleText;
    private Button useSslCheck;
    private Text dataPathText;
    private Text metadataSchemaText;
    private Text aliasText;
    private Text defaultSchemaText;
    private Button discoverCheck;
    private Button discoverDatabasesCheck;

    public DuckLakeConnectionPageAdvanced() {
        setTitle("DuckLake storage");
    }

    @Override
    public void createControl(Composite parent) {
        Composite group = new Composite(parent, SWT.NONE);
        group.setLayout(new GridLayout(1, false));
        group.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite s3 = UIUtils.createTitledComposite(group, "DuckLake storage (S3)", 2);
        s3.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        // createLabelText appends the colon itself.
        endpointText = UIUtils.createLabelText(s3, "S3 endpoint (host:port)", "");
        keyText = UIUtils.createLabelText(s3, "S3 access key", "");
        secretText = UIUtils.createLabelText(s3, "S3 secret key", "", SWT.BORDER | SWT.PASSWORD);
        regionText = UIUtils.createLabelText(s3, "S3 region", "");
        urlStyleText = UIUtils.createLabelText(s3, "S3 URL style", "");
        useSslCheck = UIUtils.createCheckbox(s3, "Use SSL", false);

        // Every field here is optional: with all four blank the plugin attaches whatever the
        // Postgres role can read and makes the first of those the active catalog.
        Composite lake = UIUtils.createTitledComposite(group, "DuckLake catalog (all optional)", 2);
        lake.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        dataPathText = UIUtils.createLabelText(lake, "DATA_PATH (s3://bucket/prefix/)", "");
        dataPathText.setToolTipText("Leave blank to browse existing catalogs. Setting it is what CREATES a"
            + " catalog in the Metadata schema when that schema has none. An existing catalog stores its own"
            + " storage location, and a value that does not match it is ignored with a warning.");
        metadataSchemaText = UIUtils.createLabelText(lake, "Metadata schema (Postgres)", "");
        metadataSchemaText.setToolTipText("Preferred primary catalog: the Postgres schema holding its ducklake_*"
            + " tables. Blank tries 'public' first, then the other readable catalogs. A schema the role cannot"
            + " read, or that holds no catalog, is skipped with a warning; nothing is created there unless"
            + " DATA_PATH is set.");
        aliasText = UIUtils.createLabelText(lake, "Catalog alias", "");
        aliasText.setToolTipText("Name for the catalog that ends up active. Blank = <database>.<schema>.");
        defaultSchemaText = UIUtils.createLabelText(lake, "Default schema (DuckLake)", "");
        defaultSchemaText.setToolTipText("Schema inside the active catalog that unqualified names resolve to."
            + " Blank = main. A schema that does not exist only costs a warning.");
        discoverCheck = UIUtils.createCheckbox(
            lake, "Discover and attach all DuckLake catalogs in this database", null, true, 2);
        discoverDatabasesCheck = UIUtils.createCheckbox(
            lake, "Also discover DuckLake catalogs in other databases on this server",
            "Attached as <database>.<schema>", true, 2);

        setControl(group);
        loadSettings();
    }

    @Override
    public void loadSettings() {
        DBPConnectionConfiguration cfg = getSite().getActiveDataSource().getConnectionConfiguration();
        endpointText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_ENDPOINT)));
        keyText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_KEY)));
        secretText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_SECRET)));
        regionText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_REGION)));
        urlStyleText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_S3_URL_STYLE)));
        useSslCheck.setSelection(CommonUtils.getBoolean(
            cfg.getProviderProperty(DuckLakeConstants.PROP_S3_USE_SSL), false));
        dataPathText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DATA_PATH)));
        metadataSchemaText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_METADATA_SCHEMA)));
        aliasText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS)));
        defaultSchemaText.setText(CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_DEFAULT_SCHEMA)));
        discoverCheck.setSelection(CommonUtils.getBoolean(
            cfg.getProviderProperty(DuckLakeConstants.PROP_DISCOVER_SCHEMAS), true));
        discoverDatabasesCheck.setSelection(CommonUtils.getBoolean(
            cfg.getProviderProperty(DuckLakeConstants.PROP_DISCOVER_DATABASES), true));
    }

    @Override
    public void saveSettings(@NotNull DBPDataSourceContainer dataSource) {
        DBPConnectionConfiguration cfg = dataSource.getConnectionConfiguration();
        cfg.setProviderProperty(DuckLakeConstants.PROP_S3_ENDPOINT, endpointText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_S3_KEY, keyText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_S3_SECRET, secretText.getText());
        cfg.setProviderProperty(DuckLakeConstants.PROP_S3_REGION, regionText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_S3_URL_STYLE, urlStyleText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_S3_USE_SSL, CommonUtils.toString(useSslCheck.getSelection()));
        cfg.setProviderProperty(DuckLakeConstants.PROP_DATA_PATH, dataPathText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_METADATA_SCHEMA, metadataSchemaText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_LAKE_ALIAS, aliasText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_DEFAULT_SCHEMA, defaultSchemaText.getText().trim());
        cfg.setProviderProperty(DuckLakeConstants.PROP_DISCOVER_SCHEMAS, CommonUtils.toString(discoverCheck.getSelection()));
        cfg.setProviderProperty(DuckLakeConstants.PROP_DISCOVER_DATABASES, CommonUtils.toString(discoverDatabasesCheck.getSelection()));
    }

    @Override
    public boolean isComplete() {
        return true;
    }
}
