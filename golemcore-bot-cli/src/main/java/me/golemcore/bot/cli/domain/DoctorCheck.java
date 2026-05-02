package me.golemcore.bot.cli.domain;

import java.util.Objects;

public record DoctorCheck(String name,DoctorCheckStatus status,String message){

public DoctorCheck{Objects.requireNonNull(name,"name");Objects.requireNonNull(status,"status");Objects.requireNonNull(message,"message");}}
