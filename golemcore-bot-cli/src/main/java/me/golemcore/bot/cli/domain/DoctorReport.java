package me.golemcore.bot.cli.domain;

import java.util.List;

public record DoctorReport(String status,List<DoctorCheck>checks){

public DoctorReport{checks=List.copyOf(checks);}}
