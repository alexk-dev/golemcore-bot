import type { ReactElement } from 'react';
import { FiArrowRight } from 'react-icons/fi';
import type { SkillInfo, SkillUpdateRequest } from '../../api/skills';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/field';
import { cn } from '../../lib/utils';
import { LocalSkillDetailPane } from './LocalSkillDetailPane';

interface LocalSkillsPanelProps {
  detail: SkillInfo | undefined;
  detailError: boolean;
  detailLoading: boolean;
  filteredSkills: SkillInfo[];
  onDelete: () => void;
  onOpenMarketplace: () => void;
  onRefetchDetail: () => void;
  onSave: (request: SkillUpdateRequest) => Promise<SkillInfo>;
  onSearchChange: (query: string) => void;
  onSelectSkill: (name: string) => void;
  searchQuery: string;
  selectedSkillName: string | null;
  updatePending: boolean;
  deletePending: boolean;
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

export function LocalSkillsPanel({
  detail,
  detailError,
  detailLoading,
  filteredSkills,
  onDelete,
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

      <LocalSkillDetailPane
        detail={detail}
        selectedSkillName={selectedSkillName}
        detailLoading={detailLoading}
        detailError={detailError}
        onRefetchDetail={onRefetchDetail}
        updatePending={updatePending}
        onSave={onSave}
        deletePending={deletePending}
        onDelete={onDelete}
      />
    </div>
  );
}
