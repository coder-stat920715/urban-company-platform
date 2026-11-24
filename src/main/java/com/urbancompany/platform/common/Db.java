package com.urbancompany.platform.common;

import java.sql.Timestamp;
import java.time.Instant;

/** JDBC helpers (pgjdbc does not bind java.time.Instant directly). */
public final class Db {
    private Db() {}
    public static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    public static Instant instant(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
