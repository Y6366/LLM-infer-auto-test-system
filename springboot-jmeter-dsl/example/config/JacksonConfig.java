package com.example.shop.config;

import com.example.shop.config.jackson.CodeEnum;
import com.example.shop.config.jackson.CodeEnumSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;

import java.time.ZoneId;
import java.util.TimeZone;

@Configuration
public class JacksonConfig {

    /**
     * 推荐做法：通过 Builder 自定义全局 ObjectMapper（Spring Boot 会自动应用）
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return (Jackson2ObjectMapperBuilder builder) -> {
            // ---- 时间：ISO-8601 + UTC，禁用时间戳 ----
            builder.modules(new JavaTimeModule());                // Java 8 日期支持
            builder.timeZone(TimeZone.getTimeZone(ZoneId.of("UTC")));
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            // ---- 空值策略：忽略 null；你也可以选择 Include.NON_EMPTY ----
            builder.serializationInclusion(JsonInclude.Include.NON_NULL);

            // ---- 反序列化健壮性 ----
            builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            builder.featuresToEnable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);

            // ---- 枚举序列化策略（两选一，或同时支持 CodeEnum 优先） ----
            // A) 输出枚举的 toString() 值（如 UPPER_CASE -> "upper_case" 可自定义 toString）
            builder.featuresToEnable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
            builder.featuresToEnable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);

            // B) 如实现了 CodeEnum 接口，则输出其 code（见下方 CodeEnum/Serializer）
            SimpleModule codeEnumModule = new SimpleModule()
                    .addSerializer(CodeEnum.class, new CodeEnumSerializer());
            builder.modules(codeEnumModule);

            // ---- 其他可选：属性排序、空 Bean 处理 ----
            builder.featuresToEnable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
            builder.featuresToDisable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        };
    }

    /**
     * 可选：如果你想显式暴露一个 ObjectMapper Bean（通常不必）
     */
    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.createXmlMapper(false).build();
    }
}
