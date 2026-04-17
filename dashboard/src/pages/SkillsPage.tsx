import { type ReactElement, useState } from 'react';
import { FiCompass, FiPackage, FiPlus } from 'react-icons/fi';
import { useSearchParams } from 'react-router-dom';
import toast from 'react-hot-toast';
import type { SkillInfo, SkillUpdateRequest } from '../api/skills';
import ConfirmModal from '../components/common/ConfirmModal';
import { PageDocsLinks } from '../components/common/PageDocsLinks';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/field';
import { Modal } from '../components/ui/overlay';
import { Card, CardContent } from '../components/ui/card';
import { getDocLinks } from '../lib/docsLinks';
import { cn } from '../lib/utils';
import {
  useCreateSkill,
  useDeleteSkill,
  useSkill,
  useSkills,
  useUpdateSkill,
} from '../hooks/useSkills';
import { extractErrorMessage } from '../utils/extractErrorMessage';
import { LocalSkillsPanel } from './skills/LocalSkillsPanel';
import { SkillsMarketplacePanel } from './skills/SkillsMarketplacePanel';

const SKILL_TEMPLATE = `---
description: ""
model_tier: balanced
---

`;
const SKILL_NAME_PATTERN = /^[a-z0-9][a-z0-9-]*$/;
const SKILLS_TAB_QUERY_PARAM = 'tab';
const SKILLS_DOCS = getDocLinks(['skills', 'mcp', 'dashboard']);

type SkillsTabKey = 'local' | 'marketplace';

const LOCAL_SKILLS_TAB: SkillsTabKey = 'local';
const MARKETPLACE_SKILLS_TAB: SkillsTabKey = 'marketplace';

const SKILLS_TABS: Array<{
  key: SkillsTabKey;
  label: string;
  description: string;
  icon: typeof FiPackage;
}> = [
  {
    key: LOCAL_SKILLS_TAB,
    label: 'Installed',
    description: 'Edit the skills already available in the local workspace.',
    icon: FiPackage,
  },
  {
    key: MARKETPLACE_SKILLS_TAB,
    label: 'Marketplace',
    description: 'Install maintained artifacts from a registry directory or repository.',
    icon: FiCompass,
  },
];

function resolveSkillsTab(searchValue: string | null): SkillsTabKey {
  if (searchValue === MARKETPLACE_SKILLS_TAB) {
    return MARKETPLACE_SKILLS_TAB;
  }
  return LOCAL_SKILLS_TAB;
}

function createSkillsSearchParams(currentParams: URLSearchParams, nextTab: SkillsTabKey): URLSearchParams {
  const nextParams = new URLSearchParams(currentParams);
  if (nextTab === LOCAL_SKILLS_TAB) {
    nextParams.delete(SKILLS_TAB_QUERY_PARAM);
    return nextParams;
  }
  nextParams.set(SKILLS_TAB_QUERY_PARAM, nextTab);
  return nextParams;
}

function LoadingSkeleton(): ReactElement {
  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div className="space-y-2">
          <div className="h-3 w-24 animate-pulse rounded-full bg-muted" />
          <div className="h-8 w-40 animate-pulse rounded-full bg-muted" />
          <div className="h-4 w-96 max-w-full animate-pulse rounded-full bg-muted" />
        </div>
        <div className="h-10 w-32 animate-pulse rounded-2xl bg-muted" />
      </div>
      <Card>
        <CardContent className="grid gap-3 p-4 sm:grid-cols-3">
          {[0, 1, 2].map((entry) => (
            <div key={entry} className="space-y-3 rounded-2xl border border-border/70 bg-muted/40 p-4">
              <div className="h-4 w-24 animate-pulse rounded-full bg-muted" />
              <div className="h-3 w-full animate-pulse rounded-full bg-muted" />
              <div className="h-3 w-2/3 animate-pulse rounded-full bg-muted" />
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
}

export default function SkillsPage(): ReactElement {
  const [searchParams, setSearchParams] = useSearchParams();
  const { data: skills, isLoading } = useSkills();
  const createMutation = useCreateSkill();
  const updateMutation = useUpdateSkill();
  const deleteMutation = useDeleteSkill();

  const [selected, setSelected] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [newName, setNewName] = useState('');

  const activeTab = resolveSkillsTab(searchParams.get(SKILLS_TAB_QUERY_PARAM));
  const { data: detail, isLoading: detailLoading, isError: detailError, refetch: refetchDetail } = useSkill(selected ?? '');

  const handleTabChange = (tab: SkillsTabKey): void => {
    const nextParams = createSkillsSearchParams(searchParams, tab);
    setSearchParams(nextParams, { replace: true });
  };

  const normalizedNewName = newName.trim().toLowerCase();
  const newNameInvalid = normalizedNewName.length > 0 && !SKILL_NAME_PATTERN.test(normalizedNewName);
  const newNameExists = normalizedNewName.length > 0 && (skills ?? []).some((skill) => skill.name === normalizedNewName);
  const canCreate = normalizedNewName.length > 0 && !newNameInvalid && !newNameExists;

  if (isLoading) {
    return <LoadingSkeleton />;
  }

  const filtered = (skills ?? []).filter((skill) => skill.name.toLowerCase().includes(search.toLowerCase()));

  const handleSelectSkill = (name: string): void => {
    if (selected === name) {
      return;
    }
    setSelected(name);
  };

  const handleSave = async (request: SkillUpdateRequest): Promise<SkillInfo> => {
    if (selected == null || selected.length === 0) {
      throw new Error('No skill selected');
    }
    try {
      const updated = await updateMutation.mutateAsync({ name: selected, request });
      if (updated.name !== selected) {
        setSelected(updated.name);
      }
      toast.success('Skill saved');
      return updated;
    } catch (err: unknown) {
      toast.error(`Failed to save skill: ${extractErrorMessage(err)}`);
      throw err;
    }
  };

  const handleDelete = async (): Promise<void> => {
    if (selected == null || selected.length === 0) {
      return;
    }
    try {
      await deleteMutation.mutateAsync(selected);
      setSelected(null);
      setShowDeleteConfirm(false);
      toast.success('Skill deleted');
    } catch (err: unknown) {
      toast.error(`Failed to delete skill: ${extractErrorMessage(err)}`);
    }
  };

  const handleCreate = async (): Promise<void> => {
    if (normalizedNewName.length === 0) {
      return;
    }
    if (newNameInvalid) {
      toast.error('Skill name must match [a-z0-9][a-z0-9-]*');
      return;
    }
    if (newNameExists) {
      toast.error('Skill already exists');
      return;
    }
    try {
      await createMutation.mutateAsync({ name: normalizedNewName, content: SKILL_TEMPLATE });
      setShowCreate(false);
      setNewName('');
      setSelected(normalizedNewName);
      toast.success('Skill created');
    } catch (err: unknown) {
      toast.error(`Failed to create skill: ${extractErrorMessage(err)}`);
    }
  };

  const activeTabMeta = SKILLS_TABS.find((tab) => tab.key === activeTab) ?? SKILLS_TABS[0];

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div className="space-y-2">
          <div className="text-[0.72rem] font-semibold uppercase tracking-[0.28em] text-primary/80">
            Skill Workspace
          </div>
          <div>
            <h1 className="text-3xl font-semibold tracking-tight text-foreground">Skills</h1>
            <p className="mt-1 max-w-3xl text-sm leading-6 text-muted-foreground">
              Manage local skills and install maintained artifacts from one workspace.
            </p>
            <PageDocsLinks title="Relevant docs" docs={SKILLS_DOCS} className="mt-3" />
          </div>
        </div>

        {activeTab === LOCAL_SKILLS_TAB ? (
          <Button type="button" size="sm" onClick={() => setShowCreate(true)}>
            <FiPlus size={14} />
            New Skill
          </Button>
        ) : (
          <div className="max-w-sm text-sm leading-6 text-muted-foreground">
            {activeTabMeta.description}
          </div>
        )}
      </div>

      <Card>
        <CardContent className="p-3">
          <div className="grid gap-2 md:grid-cols-2" role="tablist" aria-label="Skills sections">
            {SKILLS_TABS.map((tab) => {
              const Icon = tab.icon;
              const isActive = activeTab === tab.key;
              return (
                <button
                  key={tab.key}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  className={cn(
                    'flex min-h-[5.5rem] items-start gap-3 rounded-2xl border px-4 py-3 text-left transition-all duration-200',
                    isActive
                      ? 'border-primary/40 bg-primary/10 text-foreground shadow-glow'
                      : 'border-border/70 bg-card/60 text-muted-foreground hover:border-primary/20 hover:bg-card',
                  )}
                  onClick={() => handleTabChange(tab.key)}
                >
                  <span
                    className={cn(
                      'mt-0.5 inline-flex h-9 w-9 items-center justify-center rounded-2xl border',
                      isActive ? 'border-primary/30 bg-primary/15 text-primary' : 'border-border/70 bg-background/70',
                    )}
                  >
                    <Icon size={16} />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-sm font-semibold text-foreground">{tab.label}</span>
                    <span className="mt-1 block text-xs leading-5 text-muted-foreground">{tab.description}</span>
                  </span>
                </button>
              );
            })}
          </div>
        </CardContent>
      </Card>

      {activeTab === MARKETPLACE_SKILLS_TAB ? (
        <SkillsMarketplacePanel />
      ) : (
        <LocalSkillsPanel
          detail={detail}
          detailError={detailError}
          detailLoading={detailLoading}
          filteredSkills={filtered}
          onDelete={() => setShowDeleteConfirm(true)}
          onOpenMarketplace={() => handleTabChange(MARKETPLACE_SKILLS_TAB)}
          onRefetchDetail={() => { void refetchDetail(); }}
          onSave={handleSave}
          onSearchChange={setSearch}
          onSelectSkill={handleSelectSkill}
          searchQuery={search}
          selectedSkillName={selected}
          updatePending={updateMutation.isPending}
          deletePending={deleteMutation.isPending}
        />
      )}

      <Modal show={showCreate} onHide={() => setShowCreate(false)} centered size="sm">
        <Modal.Header closeButton>
          <Modal.Title>New skill</Modal.Title>
        </Modal.Header>
        <Modal.Body className="space-y-3">
          <div className="space-y-2">
            <label htmlFor="new-skill-name" className="text-sm font-medium text-foreground">
              Skill name
            </label>
            <Input
              id="new-skill-name"
              value={newName}
              onChange={(event) => setNewName(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  void handleCreate();
                }
              }}
              placeholder="my-skill"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              autoFocus
              className={cn(newNameInvalid || newNameExists ? 'border-destructive/50 focus-visible:ring-destructive' : '')}
            />
          </div>
          <p className={cn('text-xs leading-5 text-muted-foreground', (newNameInvalid || newNameExists) && 'text-destructive')}>
            {newNameInvalid
              ? 'Use lowercase letters, digits, and hyphens only, for example my-skill-name.'
              : newNameExists
                ? 'A skill with this name already exists.'
                : 'Use lowercase letters, digits, and hyphens only, for example my-skill-name.'}
          </p>
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" variant="secondary" size="sm" onClick={() => setShowCreate(false)}>
            Cancel
          </Button>
          <Button type="button" size="sm" onClick={() => { void handleCreate(); }} disabled={!canCreate || createMutation.isPending}>
            {createMutation.isPending ? 'Creating...' : 'Create'}
          </Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={showDeleteConfirm}
        title="Delete skill"
        message={selected != null && selected.length > 0 ? `Skill "${selected}" will be permanently deleted. This action cannot be undone.` : ''}
        confirmLabel="Delete"
        confirmVariant="danger"
        isProcessing={deleteMutation.isPending}
        onConfirm={() => { void handleDelete(); }}
        onCancel={() => setShowDeleteConfirm(false)}
      />
    </div>
  );
}
