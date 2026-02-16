package me.golemcore.bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BotApplicationTests {

    @Test
    void shouldHaveExpectedSpringAnnotations() {
        assertNotNull(BotApplication.class.getAnnotation(SpringBootApplication.class));
        assertNotNull(BotApplication.class.getAnnotation(ConfigurationPropertiesScan.class));
        assertNotNull(BotApplication.class.getAnnotation(EnableAsync.class));
    }

    @Test
    void shouldExposeMainMethod() throws NoSuchMethodException {
        assertNotNull(BotApplication.class.getMethod("main", String[].class));
    }
}
