package me.golemcore.bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "bot.channels.telegram.enabled=false",
        "bot.browser.enabled=false",
        "bot.llm.provider=none",
        "bot.voice.enabled=false"
})
class BotApplicationTests {

    @Test
    void contextLoads() {
        // Context loads successfully with all external dependencies disabled
    }

}
