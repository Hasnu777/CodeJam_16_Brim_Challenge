package com.brimap.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/** Money convention: BigDecimal travels as a plain string ("4802.50"), so JavaScript never rounds it as a float. */
@Configuration
public class JacksonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer bigDecimalAsString() {
        SimpleModule m = new SimpleModule();
        m.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal v, JsonGenerator g, SerializerProvider p) throws IOException {
                g.writeString(v.toPlainString());
            }
        });
        return b -> b.modulesToInstall(m);
    }
}
