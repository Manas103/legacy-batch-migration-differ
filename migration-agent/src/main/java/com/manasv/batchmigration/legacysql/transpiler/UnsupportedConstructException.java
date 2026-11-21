package com.manasv.batchmigration.legacysql.transpiler;

/** Thrown by LegacySqlTranspiler when a statement uses a construct the
 * deterministic transpiler deliberately does not translate. The message is
 * the exact construct name, not a generic parse failure, so a downstream
 * review queue can group and prioritize refusals by cause. */
public class UnsupportedConstructException extends RuntimeException {
    public UnsupportedConstructException(String constructName) {
        super(constructName);
    }
}
