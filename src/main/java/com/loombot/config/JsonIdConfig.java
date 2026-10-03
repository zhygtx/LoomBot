package com.loombot.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdScalarSerializer;

/** JSON 协议中的 Long 始终按字符串传输，避免浏览器 Number 丢失雪花 ID 精度。 */
@Configuration
public class JsonIdConfig {

    @Bean
    JsonMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("long-as-string");
            module.addSerializer(Long.class, new LongAsStringSerializer());
            module.addSerializer(Long.TYPE, new LongAsStringSerializer());
            builder.addModule(module);
        };
    }

    private static final class LongAsStringSerializer extends StdScalarSerializer<Long> {
        private LongAsStringSerializer() {
            super(Long.class);
        }

        @Override
        public void serialize(Long value, JsonGenerator generator, SerializationContext context)
                throws JacksonException {
            generator.writeString(value.toString());
        }
    }
}
