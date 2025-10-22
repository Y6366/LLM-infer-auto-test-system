package com.example.shop.common.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class JsonUtil {
    private JsonUtil() {}

    private static final ObjectMapper MAPPER = build();

    private static ObjectMapper build() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        om.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        return om;
    }

    public static ObjectMapper mapper() { return MAPPER; }

    public static String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (JsonProcessingException e) { throw new RuntimeException("JSON serialize error", e); }
    }

    public static String toPrettyJson(Object obj) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj); }
        catch (JsonProcessingException e) { throw new RuntimeException("JSON serialize error", e); }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (IOException e) { throw new RuntimeException("JSON deserialize error", e); }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try { return MAPPER.readValue(json, typeRef); }
        catch (IOException e) { throw new RuntimeException("JSON deserialize error", e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> toMap(Object obj) {
        return MAPPER.convertValue(obj, Map.class);
    }

    public static <T> T deepClone(T obj, Class<T> type) {
        return fromJson(toJson(obj), type);
    }

    public static <T> List<T> toList(String json, Class<T> elemType) {
        JavaType jt = MAPPER.getTypeFactory().constructCollectionType(List.class, elemType);
        try { return MAPPER.readValue(json, jt); }
        catch (IOException e) { throw new RuntimeException("JSON deserialize list error", e); }
    }

    public static JsonNode readTree(String json) {
        try { return MAPPER.readTree(json); }
        catch (IOException e) { throw new RuntimeException("JSON read tree error", e); }
    }
}
