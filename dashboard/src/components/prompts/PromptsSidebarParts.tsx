import type { DragEvent, KeyboardEvent, ReactElement } from 'react';
import { FiChevronDown, FiChevronUp, FiLock, FiMenu, FiPlus } from 'react-icons/fi';
import type { PromptSection } from '../../api/prompts';
import { cn } from '../../lib/utils';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Card, CardContent } from '../ui/card';
import { Input } from '../ui/field';
import type { PromptMetrics } from './promptFormUtils';

export interface PromptCatalogSummaryProps {
  metrics: PromptMetrics;
}

export function PromptCatalogSummary({ metrics }: PromptCatalogSummaryProps): ReactElement {
  return (
    <div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-1 2xl:grid-cols-3">
      <div className="rounded-2xl border border-border/80 bg-card/80 px-4 py-3">
        <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Total</div>
        <div className="mt-2 text-2xl font-semibold text-foreground">{metrics.total}</div>
      </div>
      <div className="rounded-2xl border border-border/80 bg-card/80 px-4 py-3">
        <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Enabled</div>
        <div className="mt-2 text-2xl font-semibold text-foreground">{metrics.enabled}</div>
      </div>
      <div className="rounded-2xl border border-border/80 bg-card/80 px-4 py-3">
        <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Protected</div>
        <div className="mt-2 text-2xl font-semibold text-foreground">{metrics.protected}</div>
      </div>
    </div>
  );
}

export interface PromptCreateCardProps {
  sectionCount: number;
  newPromptName: string;
  isCreating: boolean;
  hasExistingPrompt: boolean;
  isValidPromptName: boolean;
  onNameChange: (value: string) => void;
  onCreate: () => void;
  onKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void;
}

export function PromptCreateCard({
  sectionCount,
  newPromptName,
  isCreating,
  hasExistingPrompt,
  isValidPromptName,
  onNameChange,
  onCreate,
  onKeyDown,
}: PromptCreateCardProps): ReactElement {
  return (
    <div className="rounded-2xl border border-dashed border-border/80 bg-muted/20 p-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-foreground">New Prompt</div>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">Use lowercase names with hyphens.</p>
        </div>
        <Badge variant="info">{sectionCount}</Badge>
      </div>

      <div className="mt-4 flex gap-2">
        <Input
          value={newPromptName}
          onChange={(event) => onNameChange(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder="custom-guardrails"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          className="flex-1"
        />
        <Button
          size="sm"
          onClick={onCreate}
          disabled={isCreating || newPromptName.length === 0 || !isValidPromptName || hasExistingPrompt}
        >
          <FiPlus size={14} />
          Create
        </Button>
      </div>

      {!isValidPromptName && (
        <p className="mt-2 text-sm text-destructive">Name must match `[a-z0-9][a-z0-9-]*`.</p>
      )}
      {isValidPromptName && hasExistingPrompt && (
        <p className="mt-2 text-sm text-destructive">A prompt with this name already exists.</p>
      )}
    </div>
  );
}

export interface PromptCatalogItemProps {
  section: PromptSection;
  isSelected: boolean;
  isDragging: boolean;
  isDropTarget: boolean;
  canReorder: boolean;
  previousName: string | null;
  nextName: string | null;
  onSelect: (section: PromptSection) => void;
  onMove: (sourceName: string, targetName: string) => void;
  onDragStart: (event: DragEvent<HTMLDivElement>, sourceName: string) => void;
  onDragOver: (event: DragEvent<HTMLDivElement>, targetName: string) => void;
  onDragLeave: (targetName: string) => void;
  onDrop: (event: DragEvent<HTMLDivElement>, targetName: string) => void;
  onDragEnd: () => void;
}

export function PromptCatalogItem({
  section,
  isSelected,
  isDragging,
  isDropTarget,
  canReorder,
  previousName,
  nextName,
  onSelect,
  onMove,
  onDragStart,
  onDragOver,
  onDragLeave,
  onDrop,
  onDragEnd,
}: PromptCatalogItemProps): ReactElement {
  return (
    <div
      draggable={canReorder}
      onDragStart={(event) => onDragStart(event, section.name)}
      onDragOver={(event) => onDragOver(event, section.name)}
      onDragLeave={() => onDragLeave(section.name)}
      onDrop={(event) => onDrop(event, section.name)}
      onDragEnd={onDragEnd}
      className={cn(
        'rounded-3xl border p-4 transition-all duration-200',
        isSelected
          ? 'border-primary/60 bg-primary/10 shadow-soft'
          : 'border-border/80 bg-card/70 hover:border-primary/30 hover:bg-card',
        isDragging && 'opacity-55',
        isDropTarget && 'border-primary bg-primary/5 shadow-soft'
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <button type="button" onClick={() => onSelect(section)} className="min-w-0 flex-1 text-left">
          <div className="flex items-center gap-2">
            <span
              className={cn(
                'inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-full border border-border/70 text-muted-foreground',
                canReorder ? 'cursor-grab' : 'opacity-50'
              )}
              aria-hidden="true"
            >
              <FiMenu size={13} />
            </span>
            <div className="truncate text-sm font-semibold text-foreground">{section.name}</div>
          </div>
          <p className="mt-1 text-sm leading-6 text-muted-foreground">
            {section.description.length > 0 ? section.description : 'No description'}
          </p>
        </button>

        <div className="flex shrink-0 items-start gap-2">
          <Badge variant={section.enabled ? 'success' : 'secondary'}>P{section.order}</Badge>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={() => {
                if (previousName != null) {
                  onMove(section.name, previousName);
                }
              }}
              disabled={!canReorder || previousName == null}
              title="Move earlier"
              aria-label={`Move ${section.name} earlier`}
            >
              <FiChevronUp size={14} />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => {
                if (nextName != null) {
                  onMove(section.name, nextName);
                }
              }}
              disabled={!canReorder || nextName == null}
              title="Move later"
              aria-label={`Move ${section.name} later`}
            >
              <FiChevronDown size={14} />
            </Button>
          </div>
        </div>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Badge variant={section.enabled ? 'success' : 'warning'}>
          {section.enabled ? 'enabled' : 'disabled'}
        </Badge>
        {!section.deletable && (
          <Badge variant="secondary" className="gap-1">
            <FiLock size={11} />
            protected
          </Badge>
        )}
      </div>
    </div>
  );
}

export interface PromptCatalogListProps {
  sections: PromptSection[];
  selectedName: string | null;
  draggingName: string | null;
  dropTargetName: string | null;
  canReorder: boolean;
  onSelect: (section: PromptSection) => void;
  onMove: (sourceName: string, targetName: string) => void;
  onDragStart: (event: DragEvent<HTMLDivElement>, sourceName: string) => void;
  onDragOver: (event: DragEvent<HTMLDivElement>, targetName: string) => void;
  onDragLeave: (targetName: string) => void;
  onDrop: (event: DragEvent<HTMLDivElement>, targetName: string) => void;
  onDragEnd: () => void;
}

export function PromptCatalogList({
  sections,
  selectedName,
  draggingName,
  dropTargetName,
  canReorder,
  onSelect,
  onMove,
  onDragStart,
  onDragOver,
  onDragLeave,
  onDrop,
  onDragEnd,
}: PromptCatalogListProps): ReactElement {
  if (sections.length === 0) {
    return (
      <Card>
        <CardContent className="py-8 text-center text-sm text-muted-foreground">
          No prompt sections match the current filter.
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-2">
      {sections.map((section, index) => (
        <PromptCatalogItem
          key={section.name}
          section={section}
          isSelected={selectedName === section.name}
          isDragging={draggingName === section.name}
          isDropTarget={dropTargetName === section.name && draggingName !== section.name}
          canReorder={canReorder}
          previousName={index > 0 ? sections[index - 1].name : null}
          nextName={index < sections.length - 1 ? sections[index + 1].name : null}
          onSelect={onSelect}
          onMove={onMove}
          onDragStart={onDragStart}
          onDragOver={onDragOver}
          onDragLeave={onDragLeave}
          onDrop={onDrop}
          onDragEnd={onDragEnd}
        />
      ))}
    </div>
  );
}
