/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.ui;

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.ext.ducklake.model.DuckLakeConstants;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

/**
 * The "Query guard" group on the DuckLake storage tab: the on/off switch and the warning threshold.
 * Kept out of {@link DuckLakeConnectionPageAdvanced} so the page only has to create, load and save
 * it. Uses only the String constants of {@link DuckLakeConstants}, which javac inlines, so this
 * bundle still needs no class from the model bundle at run time.
 */
final class DuckLakeQueryGuardGroup {

    private Button enabledCheck;
    private Text thresholdText;

    void create(Composite parent) {
        Composite guard = UIUtils.createTitledComposite(parent, "Query guard", 2);
        guard.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        enabledCheck = UIUtils.createCheckbox(
            guard, "Warn before queries that read a lot from object storage",
            "Runs EXPLAIN first, estimates the bytes the query will pull from the catalog's file sizes, and asks"
                + " before running it when that is above the threshold. After each query the Output panel shows"
                + " what was actually read.",
            true, 2);
        thresholdText = UIUtils.createLabelText(guard, "Warn above (MB)", "");
        thresholdText.setToolTipText("Estimated read size above which the guard asks first. Default "
            + DuckLakeConstants.DEF_GUARD_THRESHOLD_MB + ".");
        enabledCheck.addListener(org.eclipse.swt.SWT.Selection, e -> thresholdText.setEnabled(enabledCheck.getSelection()));
    }

    void load(DBPConnectionConfiguration cfg) {
        // Unset (a connection saved before 1.3.0, or a new one) means on.
        enabledCheck.setSelection(CommonUtils.getBoolean(
            cfg.getProviderProperty(DuckLakeConstants.PROP_GUARD_ENABLED), true));
        String threshold = CommonUtils.notEmpty(cfg.getProviderProperty(DuckLakeConstants.PROP_GUARD_THRESHOLD_MB)).trim();
        thresholdText.setText(threshold.isEmpty() ? String.valueOf(DuckLakeConstants.DEF_GUARD_THRESHOLD_MB) : threshold);
        thresholdText.setEnabled(enabledCheck.getSelection());
    }

    void save(DBPConnectionConfiguration cfg) {
        cfg.setProviderProperty(DuckLakeConstants.PROP_GUARD_ENABLED, CommonUtils.toString(enabledCheck.getSelection()));
        cfg.setProviderProperty(DuckLakeConstants.PROP_GUARD_THRESHOLD_MB, validThreshold(thresholdText.getText()));
    }

    /** A positive number, or the default; the model side applies the same rule when it reads it. */
    private static String validThreshold(String text) {
        String value = CommonUtils.notEmpty(text).trim();

        try {
            double mb = Double.parseDouble(value);

            if (mb > 0 && !Double.isInfinite(mb)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fall back to the default below
        }

        return String.valueOf(DuckLakeConstants.DEF_GUARD_THRESHOLD_MB);
    }
}
