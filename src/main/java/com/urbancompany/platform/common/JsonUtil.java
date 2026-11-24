package com.urbancompany.platform.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Small Jackson-2 based helper used for outbox payloads and webhooks (independent of MVC's mapper). */
public final class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonUtil() {}

    public static String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException("JSON serialization failed", e); }
    }
    public static Map<String, Object> toMap(String json) {
        try { return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }
}
