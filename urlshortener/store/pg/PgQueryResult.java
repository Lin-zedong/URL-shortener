package urlshortener.store.pg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Результат SQL-запроса через простой PostgreSQL wire client.
 */
public record PgQueryResult(
        List<String> columns,
        List<List<String>> rows,
        String commandTag
) {

    public PgQueryResult {
        columns = columns == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(columns));
        List<List<String>> normalizedRows = new ArrayList<>();
        if (rows != null) {
            for (List<String> row : rows) {
                normalizedRows.add(Collections.unmodifiableList(new ArrayList<>(row)));
            }
        }
        rows = Collections.unmodifiableList(normalizedRows);
    }

    public boolean hasRows() {
        return !rows.isEmpty();
    }

    public List<String> firstRow() {
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    public String firstValue() {
        List<String> firstRow = firstRow();
        return firstRow.isEmpty() ? null : firstRow.get(0);
    }
}
