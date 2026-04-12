package me.golemcore.bot.port.outbound;

/**
 * Domain-facing password hashing contract.
 */
public interface PasswordHashPort {

    String encode(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
