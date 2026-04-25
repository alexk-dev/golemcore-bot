package me.golemcore.bot.domain.service;

import org.springframework.stereotype.Service;

@Service
public class JvmExitService {

    @SuppressWarnings({ "PMD.DoNotTerminateVM", "java:S1147" })
    public void exit(int statusCode) {
        System.exit(statusCode); // NOSONAR
    }
}
