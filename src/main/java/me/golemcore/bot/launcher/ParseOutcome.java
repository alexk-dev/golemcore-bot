package me.golemcore.bot.launcher;

record ParseOutcome(LauncherArguments launcherArguments,int exitCode,boolean shouldExit){

static ParseOutcome success(LauncherArguments launcherArguments){return new ParseOutcome(launcherArguments,0,false);}

static ParseOutcome exit(int exitCode){return new ParseOutcome(LauncherArguments.empty(),exitCode,true);}}
