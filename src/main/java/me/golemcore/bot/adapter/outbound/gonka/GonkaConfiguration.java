package me.golemcore.bot.adapter.outbound.gonka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GonkaConfiguration {

    @Bean
    public static GonkaRequestSigner gonkaRequestSigner() {
        return new GonkaRequestSigner();
    }
}
