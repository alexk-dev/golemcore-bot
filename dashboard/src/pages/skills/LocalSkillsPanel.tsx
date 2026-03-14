import type { ReactElement } from 'react';
import { FiArrowRight, FiCpu, FiRefreshCw, FiSave, FiTrash2 } from 'react-icons/fi';
import type { SkillInfo } from '../../api/skills';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Textarea, Input } from '../../components/ui/field';
import { Alert } from '../../components/ui/alert';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { cn } from '../../lib/utils';

interface LocalSkillsPanelProps {
  detail: SkillInfo | undefined;
  detailError: boolean;
  detailLoading: boolean;
  editorContent: string;
  filteredSkills: SkillInfo[];
  isSkillDirty: boolean;
  onDelete: () => void;
  onEditorChange: (content: string) => void;
  onOpenMarketplace: () => void;
  onRefetchDetail: () => void;
  onSave: () => void;
  onSearchChange: (query: string) => void;
  onSelectSkill: (name: string) => void;
  searchQuery: string;
  selectedSkillName: string | null;
  updatePending: boolean;
  deletePending: boolean;
}

interface LocalSkillDetailPaneProps {
  detail: SkillInfo | undefined;
  selectedSkillName: string | null;
  detailLoading: boolean;
  detailError: boolean;
  onRefetchDetail: () => void;
  editorContent: string;
  onEditorChange: (content: string) => void;
  isSkillDirty: boolean;
  updatePending: boolean;
  onSave: () => void;
  deletePending: boolean;
  onDelete: () => void;
}

function LoadingState({ message }: { message: string }): ReactElement {
  return (
    <Card className="min-h-[28rem]">
      <CardContent className="flex min-h-[28rem] items-center justify-center">
        <div className="flex items-center gap-3 text-sm text-muted-foreground">
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />
          <span>{message}</span>
        </div>
      </CardContent>
    </Card>
  );
}

function EmptyState({ onOpenMarketplace }: { onOpenMarketplace: () => void }): ReactElement {
  return (
    <div className="rounded-2xl border border-dashed border-border/80 bg-muted/20 px-4 py-8 text-center">
      <p className="text-sm text-muted-foreground">No skills match this filter.</p>
      <Button type="button" variant="link" className="mt-2" onClick={onOpenMarketplace}>
        Open marketplace
        <FiArrowRight size={14} />
      </Button>
    </div>
  );
}

function SkillListItem({
  skill,
  selected,
  onSelect,
}: {
  skill: SkillInfo;
  selected: boolean;
  onSelect: () => void;
}): ReactElement {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'w-full rounded-2xl border px-4 py-3 text-left transition-all duration-200',
        selected
          ? 'border-primary/35 bg-primary/10 shadow-glow'
          : 'border-border/70 bg-card/60 hover:border-primary/20 hover:bg-card',
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-foreground">{skill.name}</div>
          <div className="mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground">
            {skill.description?.trim().length ? skill.description : 'No description provided.'}
          </div>
        </div>
        <div className="flex shrink-0 flex-wrap justify-end gap-1">
          <Badge variant={skill.available ? 'success' : 'secondary'}>
            {skill.available ? 'On' : 'Off'}
          </Badge>
          {skill.hasMcp && <Badge variant="info">MCP</Badge>}
          {skill.modelTier != null && skill.modelTier.length > 0 && skill.modelTier !== 'balanced' && (
            <Badge variant="warning">{skill.modelTier}</Badge>
          )}
        </div>
      </div>
    </button>
  );
}

function renderDetailPane({
  detail,
  selectedSkillName,
  detailLoading,
  detailError,
  onRefetchDetail,
  editorContent,
  onEditorChange,
  isSkillDirty,
  updatePending,
  onSave,
  deletePending,
  onDelete,
}: LocalSkillDetailPaneProps): ReactElement {
  if (selectedSkillName != null && selectedSkillName.length > 0 && detailLoading) {
    return <LoadingState message="Loading skill..." />;
  }

  if (selectedSkillName != null && selectedSkillName.length > 0 && detailError) {
    return (
      <Card className="min-h-[28rem]">
        <CardContent className="flex min-h-[28rem] items-center justify-center">
          <div className="w-full max-w-md space-y-4 text-center">
            <Alert variant="danger">Failed to load the selected skill.</Alert>
            <Button type="button" size="sm" variant="secondary" onClick={onRefetchDetail}>
              <FiRefreshCw size={14} />
              Retry
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  if (selectedSkillName != null && selectedSkillName.length > 0 && detail != null) {
    return (
      <Card className="min-h-[28rem]">
        <CardHeader className="items-start">
          <div className="space-y-2">
            <CardTitle className="text-lg">{selectedSkillName}</CardTitle>
            <CardDescription>
              Edit the source of `SKILL.md` directly. Changes are saved to the local workspace.
            </CardDescription>
          </div>
          <div className="flex flex-wrap justify-end gap-1">
            {detail.hasMcp && <Badge variant="info">MCP</Badge>}
            {detail.modelTier != null && detail.modelTier.length > 0 && (
              <Badge variant="secondary">{detail.modelTier}</Badge>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <label htmlFor="skill-editor" className="text-sm font-medium text-foreground">
              SKILL.md content
            </label>
            <Textarea
              id="skill-editor"
              rows={18}
              value={editorContent}
              onChange={(event) => onEditorChange(event.target.value)}
              className="min-h-[28rem] font-mono text-xs leading-6"
            />
          </div>

          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              size="sm"
              onClick={onSave}
              disabled={!isSkillDirty || updatePending}
            >
              {updatePending ? (
                <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
              ) : (
                <FiSave size={14} />
              )}
              {updatePending ? 'Saving...' : 'Save'}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="destructive"
              onClick={onDelete}
              disabled={deletePending}
            >
              <FiTrash2 size={14} />
              {deletePending ? 'Deleting...' : 'Delete'}
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="min-h-[28rem]">
      <CardContent className="flex min-h-[28rem] items-center justify-center">
        <div className="space-y-3 text-center">
          <div className="mx-auto inline-flex h-12 w-12 items-center justify-center rounded-2xl border border-border/80 bg-muted/30 text-muted-foreground">
            <FiCpu size={18} />
          </div>
          <div>
            <h3 className="text-base font-semibold text-foreground">Select a skill to edit</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              Pick a local skill from the list to inspect metadata and update the `SKILL.md` body.
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export function LocalSkillsPanel({
  detail,
  detailError,
  detailLoading,
  editorContent,
  filteredSkills,
  isSkillDirty,
  onDelete,
  onEditorChange,
  onOpenMarketplace,
  onRefetchDetail,
  onSave,
  onSearchChange,
  onSelectSkill,
  searchQuery,
  selectedSkillName,
  updatePending,
  deletePending,
}: LocalSkillsPanelProps): ReactElement {
  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(18rem,24rem)_1fr]">
      <Card className="overflow-hidden">
        <CardHeader className="items-start">
          <div className="space-y-2">
            <CardTitle>Installed skills</CardTitle>
            <CardDescription>Search the local workspace and choose a skill to edit.</CardDescription>
          </div>
          <Badge variant="secondary">{filteredSkills.length}</Badge>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <label htmlFor="skills-search" className="text-sm font-medium text-foreground">
              Search
            </label>
            <Input
              id="skills-search"
              placeholder="Search skills..."
              value={searchQuery}
              onChange={(event) => onSearchChange(event.target.value)}
            />
          </div>

          <div className="space-y-2">
            {filteredSkills.map((skill) => (
              <SkillListItem
                key={skill.name}
                skill={skill}
                selected={selectedSkillName === skill.name}
                onSelect={() => onSelectSkill(skill.name)}
              />
            ))}
            {filteredSkills.length === 0 && <EmptyState onOpenMarketplace={onOpenMarketplace} />}
          </div>
        </CardContent>
      </Card>

      {renderDetailPane({
        detail,
        selectedSkillName,
        detailLoading,
        detailError,
        onRefetchDetail,
        editorContent,
        onEditorChange,
        isSkillDirty,
        updatePending,
        onSave,
        deletePending,
        onDelete,
      })}
    </div>
  );
}
