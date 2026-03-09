import type { ReactElement } from 'react';
import { FiPlus, FiTrash2 } from 'react-icons/fi';
import { Button } from '../../../components/ui/button';
import { Input, Select } from '../../../components/ui/field';
import type { ReasoningLevelDraft } from './modelCatalogTypes';

interface ReasoningLevelsEditorProps {
  defaultLevel: string;
  levels: ReasoningLevelDraft[];
  onDefaultLevelChange: (value: string) => void;
  onAddLevel: () => void;
  onRemoveLevel: (index: number) => void;
  onUpdateLevel: (index: number, field: 'level' | 'maxInputTokens', value: string) => void;
}

export function ReasoningLevelsEditor({
  defaultLevel,
  levels,
  onDefaultLevelChange,
  onAddLevel,
  onRemoveLevel,
  onUpdateLevel,
}: ReasoningLevelsEditorProps): ReactElement {
  const levelOptions = levels
    .map((level) => level.level.trim())
    .filter((level) => level.length > 0);

  return (
    <div className="space-y-4 rounded-[1.25rem] border border-border/80 bg-muted/20 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-foreground">Reasoning Levels</h3>
          <p className="text-sm text-muted-foreground">
            Configure named reasoning presets and the token limit for each one.
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={onAddLevel}>
          <FiPlus size={14} />
          Add Level
        </Button>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_14rem]">
        <div className="space-y-3">
          {levels.map((level, index) => (
            <div
              key={`reasoning-level-${index}`}
              className="grid gap-3 rounded-2xl border border-border/70 bg-card/80 p-3 md:grid-cols-[minmax(0,1fr)_12rem_auto]"
            >
              <div className="space-y-2">
                <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Level Name
                </label>
                <Input
                  value={level.level}
                  onChange={(event) => onUpdateLevel(index, 'level', event.target.value)}
                  placeholder="low"
                />
              </div>

              <div className="space-y-2">
                <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Max Tokens
                </label>
                <Input
                  value={level.maxInputTokens}
                  onChange={(event) => onUpdateLevel(index, 'maxInputTokens', event.target.value)}
                  inputMode="numeric"
                  placeholder="128000"
                />
              </div>

              <div className="flex items-end">
                <Button
                  variant="secondary"
                  size="icon"
                  onClick={() => onRemoveLevel(index)}
                  aria-label={`Remove reasoning level ${level.level || index + 1}`}
                >
                  <FiTrash2 size={15} />
                </Button>
              </div>
            </div>
          ))}

          {levels.length === 0 && (
            <div className="rounded-2xl border border-dashed border-border/80 bg-card/60 px-4 py-5 text-sm text-muted-foreground">
              No reasoning levels configured yet.
            </div>
          )}
        </div>

        <div className="space-y-2">
          <label className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
            Default Level
          </label>
          <Select value={defaultLevel} onChange={(event) => onDefaultLevelChange(event.target.value)}>
            <option value="">Select default level</option>
            {levelOptions.map((level, index) => (
              <option key={`${level}-${index}`} value={level}>
                {level}
              </option>
            ))}
          </Select>
          <p className="text-sm text-muted-foreground">
            The router will use this level when no explicit reasoning override is set.
          </p>
        </div>
      </div>
    </div>
  );
}
