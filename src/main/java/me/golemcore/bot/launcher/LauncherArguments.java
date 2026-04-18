package me.golemcore.bot.launcher;

import java.util.List;

record LauncherArguments(String storagePath,String updatesPath,String bundledJar,String serverPort,List<String>explicitJavaOptions,List<String>applicationArguments){

static LauncherArguments empty(){return new LauncherArguments(null,null,null,null,List.of(),List.of());}}
