import { useMemo, useState } from 'react';
import type { AvailableModel } from '../../api/models';
import type { CommandDefinition, CommandSuggestion, ParsedCommandInput } from './chatInputTypes';

const CHAT_COMMANDS: CommandDefinition[] = [
  { name: 'help', description: 'Show available commands' },
  { name: 'skills', description: 'List available skills' },
  { name: 'tools', description: 'List enabled tools' },
  { name: 'status', description: 'Show session status' },
  { name: 'new', description: 'Start a new conversation' },
  { name: 'reset', description: 'Reset conversation' },
  { name: 'compact', description: 'Compact conversation history' },
  { name: 'tier', description: 'Set model tier' },
  { name: 'model', description: 'Configure per-tier models' },
  { name: 'stop', description: 'Stop current run' },
  { name: 'auto', description: 'Toggle auto mode' },
  { name: 'goals', description: 'List goals' },
  { name: 'goal', description: 'Create a goal' },
  { name: 'tasks', description: 'List tasks' },
  { name: 'diary', description: 'Show diary entries' },
  { name: 'schedule', description: 'Manage schedules' },
  { name: 'plan', description: 'Plan mode control' },
  { name: 'plans', description: 'List plans' },
];

const TIER_VALUES = ['balanced', 'smart', 'coding', 'deep'];

interface SuggestionOption {
  value: string;
  description: string;
  label?: string;
}

function parseCommandInput(text: string): ParsedCommandInput | null {
  const firstLine = text.split('\n')[0] ?? '';
  if (!firstLine.startsWith('/')) {
    return null;
  }

  const commandPart = firstLine.slice(1);
  const hasTrailingSpace = /\s$/.test(commandPart);
  const trimmed = commandPart.trim();
  const tokens = trimmed.length > 0 ? trimmed.split(/\s+/) : [];

  if (tokens.length === 0) {
    return { tokens: [], activeTokenIndex: 0, activeQuery: '' };
  }

  if (hasTrailingSpace) {
    return { tokens, activeTokenIndex: tokens.length, activeQuery: '' };
  }

  const activeTokenIndex = Math.max(0, tokens.length - 1);
  const activeQuery = (tokens[activeTokenIndex] ?? '').toLowerCase();
  return { tokens, activeTokenIndex, activeQuery };
}

function withAppliedToken(
  currentText: string,
  tokens: string[],
  activeTokenIndex: number,
  value: string,
): string {
  const nextTokens = [...tokens];
  if (activeTokenIndex >= nextTokens.length) {
    nextTokens.push(value);
  } else {
    nextTokens[activeTokenIndex] = value;
  }
  const commandLine = `/${nextTokens.join(' ')} `;
  const newlineIndex = currentText.indexOf('\n');
  if (newlineIndex < 0) {
    return commandLine;
  }
  return `${commandLine}${currentText.slice(newlineIndex)}`;
}

function toSuggestions(
  options: SuggestionOption[],
  parsed: ParsedCommandInput,
  text: string,
): CommandSuggestion[] {
  return options.map((option) => ({
    key: `${parsed.activeTokenIndex}:${option.value}`,
    label: option.label ?? option.value,
    description: option.description,
    insertText: withAppliedToken(text, parsed.tokens, parsed.activeTokenIndex, option.value),
  }));
}

function filterValues(values: string[], query: string, description: string): SuggestionOption[] {
  return values
    .filter((value) => value.startsWith(query))
    .map((value) => ({ value, description }));
}

function resolveTierSubcommands(parsed: ParsedCommandInput): SuggestionOption[] {
  const { activeTokenIndex, activeQuery, tokens } = parsed;
  if (activeTokenIndex === 1) {
    return filterValues(TIER_VALUES, activeQuery, 'Model tier');
  }
  if (activeTokenIndex === 2 && (tokens[1] ?? '').length > 0) {
    return filterValues(['force'], activeQuery, 'Pin selected tier');
  }
  return [];
}

function resolveModelSubcommands(
  parsed: ParsedCommandInput,
  modelIds: string[],
  reasoningLevels: string[],
): SuggestionOption[] {
  const { activeTokenIndex, activeQuery, tokens } = parsed;
  if (activeTokenIndex === 1) {
    return ['list', ...TIER_VALUES]
      .filter((value) => value.startsWith(activeQuery))
      .map((value) => ({ value, description: value === 'list' ? 'List available models' : 'Model tier' }));
  }

  const selectedTier = tokens[1] ?? '';
  const isTierSelection = TIER_VALUES.includes(selectedTier);
  if (isTierSelection && activeTokenIndex === 2) {
    const controlOptions: SuggestionOption[] = [
      { value: 'reasoning', description: 'Set reasoning level for tier model' },
      { value: 'reset', description: 'Reset tier override to default' },
    ];
    const modelOptions = modelIds.slice(0, 24).map((value) => ({ value, description: 'Available model' }));
    return [...controlOptions, ...modelOptions]
      .filter((option) => option.value.startsWith(activeQuery))
      .slice(0, 10);
  }

  if (isTierSelection && (tokens[2] ?? '') === 'reasoning' && activeTokenIndex === 3) {
    const levels = reasoningLevels.length > 0 ? reasoningLevels : ['minimal', 'low', 'medium', 'high'];
    return filterValues(levels, activeQuery, 'Reasoning level');
  }

  return [];
}

/** Simple sub-command completions keyed by command name. */
const SIMPLE_SUBCOMMANDS: Record<string, SuggestionOption[]> = {
  plan: [
    { value: 'on', description: 'Plan mode action' },
    { value: 'off', description: 'Plan mode action' },
    { value: 'status', description: 'Plan mode action' },
    { value: 'approve', description: 'Plan mode action' },
    { value: 'cancel', description: 'Plan mode action' },
    { value: 'resume', description: 'Plan mode action' },
  ],
  compact: [
    { value: '20', description: 'Keep last N messages during compaction' },
    { value: '50', description: 'Keep last N messages during compaction' },
    { value: '100', description: 'Keep last N messages during compaction' },
  ],
  schedule: [
    { value: 'list', description: 'List active schedules' },
    { value: 'goal', description: 'Schedule a goal by id' },
    { value: 'task', description: 'Schedule a task by id' },
    { value: 'delete', description: 'Delete schedule by id' },
    { value: 'help', description: 'Show schedule command help' },
  ],
  auto: [
    { value: 'on', description: 'Auto mode state' },
    { value: 'off', description: 'Auto mode state' },
  ],
  diary: [
    { value: '10', description: 'Recent entries count' },
    { value: '25', description: 'Recent entries count' },
    { value: '50', description: 'Recent entries count' },
  ],
};

function buildSuggestionOptions(
  parsed: ParsedCommandInput,
  modelIds: string[],
  reasoningLevels: string[],
): SuggestionOption[] {
  const { tokens, activeTokenIndex, activeQuery } = parsed;
  const commandName = (tokens[0] ?? '').toLowerCase();

  if (activeTokenIndex === 0) {
    return CHAT_COMMANDS
      .filter((command) => command.name.startsWith(activeQuery))
      .slice(0, 8)
      .map((command) => ({ value: command.name, label: `/${command.name}`, description: command.description }));
  }

  if (commandName === 'tier') {
    return resolveTierSubcommands(parsed);
  }

  if (commandName === 'model') {
    return resolveModelSubcommands(parsed, modelIds, reasoningLevels);
  }

  const simpleOptions = SIMPLE_SUBCOMMANDS[commandName];
  if (simpleOptions !== undefined && activeTokenIndex === 1) {
    return simpleOptions.filter((option) => option.value.startsWith(activeQuery));
  }

  return [];
}

interface ChatCommandsHook {
  suggestions: CommandSuggestion[];
  isMenuOpen: boolean;
  activeIndex: number;
  setActiveIndex: (index: number) => void;
  applySuggestion: (suggestion: CommandSuggestion) => string;
}

export function useChatCommands(
  text: string,
  availableModels: Record<string, AvailableModel[]> | undefined,
  disabled: boolean,
): ChatCommandsHook {
  const [activeIndex, setActiveIndex] = useState(0);

  const modelIds = useMemo(() => {
    const groupedModels = availableModels ?? {};
    return Object.values(groupedModels)
      .flatMap((models) => models.map((model) => model.id))
      .sort();
  }, [availableModels]);

  const modelReasoningLevels = useMemo(() => {
    const groupedModels = availableModels ?? {};
    return Array.from(new Set(
      Object.values(groupedModels)
        .flatMap((models) => models.flatMap((model) => model.reasoningLevels ?? [])),
    )).sort();
  }, [availableModels]);

  const commandInput = useMemo(() => parseCommandInput(text), [text]);

  const suggestions = useMemo(() => {
    if (commandInput === null) {
      return [] as CommandSuggestion[];
    }
    const options = buildSuggestionOptions(commandInput, modelIds, modelReasoningLevels);
    return toSuggestions(options, commandInput, text);
  }, [commandInput, modelIds, modelReasoningLevels, text]);

  const isMenuOpen = !disabled && suggestions.length > 0;

  const applySuggestion = (suggestion: CommandSuggestion): string => {
    setActiveIndex(0);
    return suggestion.insertText;
  };

  return { suggestions, isMenuOpen, activeIndex, setActiveIndex, applySuggestion };
}
