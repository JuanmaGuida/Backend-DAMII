package com.reclamos.backend.support;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JdbcSnapshot {
    private JdbcSnapshot() { }

    /** Keep every column; compare JDBC BYTEA by its bytes rather than Java array identity. */
    public static Map<String, Object> comparableRow(Map<String, Object> row) {
        var snapshot = new LinkedHashMap<>(row);
        snapshot.replaceAll((column, value) -> value instanceof byte[] bytes
                ? HexFormat.of().formatHex(bytes) : value);
        return snapshot;
    }
}
