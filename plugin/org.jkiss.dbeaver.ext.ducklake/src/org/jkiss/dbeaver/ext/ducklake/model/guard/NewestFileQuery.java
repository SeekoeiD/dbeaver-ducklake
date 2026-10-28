/*
 * DuckLake plugin for DBeaver
 * Licensed under the Apache License, Version 2.0.
 */
package org.jkiss.dbeaver.ext.ducklake.model.guard;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The query guard's "Newest file only" choice for a statement typed in the SQL editor. A bare
 * {@code SELECT * FROM arc.bronze.raw_responses} warned "8674 files, ~29 GB"; the data editor would
 * have opened the same table on its newest data file ({@link NewestFileFilter}), so the guard offers
 * that too: run the statement with the newest file's range as a WHERE.
 *
 * <p>Only a plain single-table read gets the offer, decided from the text here and from the plan
 * ({@link QueryGuardPlan#plainScan}). The text must be one statement of the form
 * {@code SELECT <list> FROM <table> [[AS] alias]}, optionally ending in a semicolon, with nothing
 * else but whitespace and {@code --} comments: no WHERE, ORDER BY, GROUP BY, LIMIT, UNION, join,
 * CTE, parenthesis, string literal or block comment. Anything the pattern does not recognise gets
 * no offer, because appending a WHERE to a statement that already has a clause the pattern missed
 * would change what the user asked for, not just how much of it is read.
 *
 * <p>Pure Java, so the matcher and the rewrite can be unit tested.
 */
public final class NewestFileQuery {

    /**
     * A statement the rewrite applies to.
     *
     * @param nameParts the table name's parts, unquoted ({@code arc}, {@code bronze}, {@code raw_responses})
     * @param alias     the table's alias, unquoted, or null
     * @param end       index in the statement just after the table name or alias, where the WHERE goes
     */
    public record Parsed(List<String> nameParts, String alias, int end) {

        /** What the WHERE qualifies the column with: the alias, else the table's last name part. */
        public String reference() {
            return alias != null ? alias : nameParts.get(nameParts.size() - 1);
        }
    }

    private static final String IDENT = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";

    /**
     * The select list holds no parenthesis, semicolon, string literal or second FROM, the last so
     * that {@code SELECT * FROM t UNION ALL SELECT * FROM u} cannot match with the first table
     * inside the "list" (a quoted column named "from" is refused with it).
     */
    private static final Pattern PLAIN_SELECT = Pattern.compile(
        "\\s*SELECT\\s+(?:(?!\\bFROM\\b)[^();'])+?\\s+FROM\\s+(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + "){0,2})"
            + "(?:\\s+(?:AS\\s+)?(" + IDENT + "))?\\s*;?\\s*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Words that end a FROM clause. The pattern would take one of them as the alias when nothing
     * follows it ({@code SELECT * FROM t LIMIT}), which is not valid SQL anyway, but never append
     * after a keyword.
     */
    private static final Set<String> KEYWORDS = Set.of(
        "WHERE", "LIMIT", "OFFSET", "ORDER", "GROUP", "HAVING", "QUALIFY", "WINDOW", "UNION", "EXCEPT",
        "INTERSECT", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "POSITIONAL", "ASOF",
        "ANTI", "SEMI", "LATERAL", "USING", "ON", "TABLESAMPLE", "SAMPLE", "PIVOT", "UNPIVOT", "AS", "FROM",
        "SELECT", "AT");

    private NewestFileQuery() {
    }

    /**
     * The statement as a plain single-table read, or null when it is anything else. Matched
     * case-insensitively on the text with its {@code --} comments blanked out, so indexes still
     * point into the original.
     */
    public static Parsed parse(String sql) {
        if (sql == null) {
            return null;
        }

        String text = blankComments(sql);

        if (text == null) {
            return null;
        }

        Matcher m = PLAIN_SELECT.matcher(text);

        if (!m.matches()) {
            return null;
        }

        String alias = null;
        int end = m.end(1);

        if (m.group(2) != null) {
            if (!m.group(2).startsWith("\"") && KEYWORDS.contains(m.group(2).toUpperCase(Locale.ROOT))) {
                return null;
            }

            alias = unquote(m.group(2));
            end = m.end(2);
        }

        List<String> parts = QueryGuardEstimate.splitQualified(stripUnquotedSpace(m.group(1)));
        return new Parsed(List.copyOf(parts), alias, end);
    }

    /**
     * The statement with {@code WHERE <ref>.<column> BETWEEN <min> AND <max>} added after the table
     * name or alias, so a trailing semicolon or {@code --} comment stays where it was.
     *
     * @param quote quotes an identifier for the connection's SQL dialect where it needs it
     */
    public static String rewrite(String sql, Parsed parsed, NewestFileFilter.Choice choice, UnaryOperator<String> quote) {
        return sql.substring(0, parsed.end()) + " WHERE " + quote.apply(parsed.reference()) + "."
            + NewestFileFilter.where(choice, quote) + sql.substring(parsed.end());
    }

    /**
     * The statement with each {@code --} comment replaced by spaces of the same length, or null
     * when it holds something the matcher must not see through: a block comment, or a string
     * literal (a {@code --} inside one is not a comment). Double-quoted identifiers are skipped.
     */
    static String blankComments(String sql) {
        StringBuilder sb = new StringBuilder(sql);
        boolean quoted = false;

        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);

            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\'' || sql.startsWith("/*", i))) {
                return null;
            } else if (!quoted && sql.startsWith("--", i)) {
                while (i < sb.length() && sb.charAt(i) != '\n') {
                    sb.setCharAt(i, ' ');
                    i++;
                }
            }
        }

        return quoted ? null : sb.toString();
    }

    /** {@code arc . "my schema" . t} as {@code arc."my schema".t}: whitespace outside quotes dropped. */
    private static String stripUnquotedSpace(String name) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);

            if (c == '"') {
                quoted = !quoted;
            }

            if (!quoted && Character.isWhitespace(c)) {
                continue;
            }

            sb.append(c);
        }

        return sb.toString();
    }

    private static String unquote(String ident) {
        if (ident.length() >= 2 && ident.startsWith("\"") && ident.endsWith("\"")) {
            return ident.substring(1, ident.length() - 1).replace("\"\"", "\"");
        }

        return ident;
    }
}
