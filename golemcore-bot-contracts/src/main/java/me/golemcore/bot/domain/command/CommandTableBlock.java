package me.golemcore.bot.domain.command;

import java.util.List;

/**
 * Transport-neutral tabular command output.
 *
 * @param columns
 *            table column labels
 * @param rows
 *            row cell values
 */
public record CommandTableBlock(List<String>columns,List<List<String>>rows)implements CommandBlock{

public CommandTableBlock{columns=columns==null||columns.isEmpty()?List.of():List.copyOf(columns);rows=rows==null||rows.isEmpty()?List.of():copyRows(rows);}

private static List<List<String>>copyRows(List<List<String>>rows){return rows.stream().map(row->row==null?List.<String>of():List.copyOf(row)).toList();}}
