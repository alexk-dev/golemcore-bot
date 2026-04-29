package me.golemcore.bot.cli.presentation;

import java.io.PrintWriter;
import java.util.stream.Collectors;
import me.golemcore.bot.cli.domain.DoctorCheck;
import me.golemcore.bot.cli.domain.DoctorReport;

public final class DoctorPresenter {

    public void renderText(DoctorReport report, PrintWriter out) {
        out.println("status: " + report.status());
        for (DoctorCheck check : report.checks()) {
            out.println(check.name() + ": " + check.status().serializedValue() + " - " + check.message());
        }
    }

    public void renderJson(DoctorReport report, PrintWriter out) {
        out.println("{\"status\":\"" + escape(report.status()) + "\",\"checks\":["
                + report.checks().stream()
                        .map(this::renderCheckJson)
                        .collect(Collectors.joining(","))
                + "]}");
    }

    private String renderCheckJson(DoctorCheck check) {
        return "{\"name\":\"" + escape(check.name()) + "\",\"status\":\""
                + escape(check.status().serializedValue()) + "\",\"message\":\""
                + escape(check.message()) + "\"}";
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
            case '"' -> builder.append("\\\"");
            case '\\' -> builder.append("\\\\");
            case '\n' -> builder.append("\\n");
            case '\r' -> builder.append("\\r");
            case '\t' -> builder.append("\\t");
            default -> builder.append(character);
            }
        }
        return builder.toString();
    }
}
