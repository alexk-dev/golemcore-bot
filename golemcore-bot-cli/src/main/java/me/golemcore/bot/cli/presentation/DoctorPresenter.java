package me.golemcore.bot.cli.presentation;

import java.io.PrintWriter;
import me.golemcore.bot.cli.domain.DoctorCheck;
import me.golemcore.bot.cli.domain.DoctorReport;

public final class DoctorPresenter {

    private final JsonPresenter jsonPresenter;

    public DoctorPresenter() {
        this(new JsonPresenter());
    }

    DoctorPresenter(JsonPresenter jsonPresenter) {
        this.jsonPresenter = jsonPresenter;
    }

    public void renderText(DoctorReport report, PrintWriter out) {
        out.println("status: " + report.status());
        for (DoctorCheck check : report.checks()) {
            out.println(check.name() + ": " + check.status().serializedValue() + " - " + check.message());
        }
    }

    public void renderJson(DoctorReport report, PrintWriter out) {
        jsonPresenter.render(report, out);
    }
}
