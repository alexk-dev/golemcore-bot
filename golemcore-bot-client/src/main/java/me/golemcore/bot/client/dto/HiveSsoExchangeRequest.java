package me.golemcore.bot.client.dto;

import lombok.Data;

@Data
public class HiveSsoExchangeRequest {
    private String code;
    private String codeVerifier;
}
