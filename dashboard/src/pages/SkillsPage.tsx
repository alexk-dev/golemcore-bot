import { type ReactElement, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, Card, Form, Modal, Placeholder } from 'react-bootstrap';
import {
  useCreateSkill,
  useDeleteSkill,
  useSkill,
  useSkills,
  useUpdateSkill,
} from '../hooks/useSkills';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/extractErrorMessage';
import ConfirmModal from '../components/common/ConfirmModal';
import { LocalSkillsPanel } from './skills/LocalSkillsPanel';
import { SkillsMarketplacePanel } from './skills/SkillsMarketplacePanel';

const SKILL_TEMPLATE = `---
description: ""
available: true
model_tier: balanced
---

`;
const SKILL_NAME_PATTERN = /^[a-z0-9][a-z0-9-]*$/;
const SKILLS_TAB_QUERY_PARAM = 'tab';

type SkillsTabKey = 'local' | 'marketplace';

const LOCAL_SKILLS_TAB: SkillsTabKey = 'local';
const MARKETPLACE_SKILLS_TAB: SkillsTabKey = 'marketplace';

function getSkillsTabClassName(tab: SkillsTabKey, activeTab: SkillsTabKey): string {
  return activeTab === tab ? 'nav-link active' : 'nav-link';
}

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
  nextParams.set(SKILLS_TAB_QUERY_PARAM, MARKETPLACE_SKILLS_TAB);
  return nextParams;
}

export default function SkillsPage(): ReactElement {
  const [searchParams, setSearchParams] = useSearchParams();
  const { data: skills, isLoading } = useSkills();
  const createMutation = useCreateSkill();
  const updateMutation = useUpdateSkill();
  const deleteMutation = useDeleteSkill();

  const [selected, setSelected] = useState<string | null>(null);
  const [editContent, setEditContent] = useState('');
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [newName, setNewName] = useState('');

  const activeTab = resolveSkillsTab(searchParams.get(SKILLS_TAB_QUERY_PARAM));
  const { data: detail, isLoading: detailLoading, isError: detailError, refetch: refetchDetail } = useSkill(selected ?? '');

  if (isLoading) {
    return (
      <div>
        <div className="section-header d-flex align-items-center justify-content-between">
          <h4 className="mb-0">Skills</h4>
        </div>
        <Card>
          <Card.Body>
            <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={3} /></Placeholder>
            <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={12} /></Placeholder>
            <Placeholder as="div" animation="glow" className="mb-2"><Placeholder xs={10} /></Placeholder>
          </Card.Body>
        </Card>
      </div>
    );
  }

  const filtered = (skills ?? []).filter((s) =>
    s.name.toLowerCase().includes(search.toLowerCase())
  );

  const handleSelect = (name: string): void => {
    setSelected(name);
  };

  const handleTabChange = (tab: SkillsTabKey): void => {
    const nextParams = createSkillsSearchParams(searchParams, tab);
    setSearchParams(nextParams, { replace: true });
  };

  // Sync editor content when detail loads
  const currentContent = selected === detail?.name ? (detail?.content ?? '') : '';
  const editorContent = selected === detail?.name && editContent === '' ? currentContent : editContent;
  const isSkillDirty = selected === detail?.name && editorContent !== currentContent;
  const normalizedNewName = newName.trim().toLowerCase();
  const newNameInvalid = normalizedNewName.length > 0 && !SKILL_NAME_PATTERN.test(normalizedNewName);
  const newNameExists = normalizedNewName.length > 0 && (skills ?? []).some((skill) => skill.name === normalizedNewName);
  const canCreate = normalizedNewName.length > 0 && !newNameInvalid && !newNameExists;

  const handleSelectAndLoad = (name: string): void => {
    handleSelect(name);
    setEditContent('');
  };

  const handleOpenMarketplaceTab = (): void => {
    handleTabChange(MARKETPLACE_SKILLS_TAB);
  };

  const handleSave = async (): Promise<void> => {
    if (selected == null || selected.length === 0 || !isSkillDirty) {
      return;
    }
    try {
      await updateMutation.mutateAsync({ name: selected, content: editorContent });
      toast.success('Skill saved');
    } catch (err: unknown) {
      toast.error(`Failed to save skill: ${extractErrorMessage(err)}`);
    }
  };

  const handleDelete = async (): Promise<void> => {
    if (selected == null || selected.length === 0) {
      return;
    }
    try {
      await deleteMutation.mutateAsync(selected);
      setSelected(null);
      setEditContent('');
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
      setEditContent('');
      toast.success('Skill created');
    } catch (err: unknown) {
      toast.error(`Failed to create skill: ${extractErrorMessage(err)}`);
    }
  };

  return (
    <div>
      <div className="section-header d-flex align-items-center justify-content-between">
        <h4 className="mb-0">Skills</h4>
        {activeTab === LOCAL_SKILLS_TAB ? (
          <Button type="button" size="sm" variant="primary" onClick={() => setShowCreate(true)}>
            + New Skill
          </Button>
        ) : (
          <div className="small text-body-secondary">Discover and install reusable skills</div>
        )}
      </div>

      <Card className="settings-card mb-3">
        <Card.Body className="py-2">
          <div className="nav nav-tabs" role="tablist" aria-label="Skills sections">
            <button
              type="button"
              className={getSkillsTabClassName(LOCAL_SKILLS_TAB, activeTab)}
              role="tab"
              aria-selected={activeTab === LOCAL_SKILLS_TAB}
              onClick={() => handleTabChange(LOCAL_SKILLS_TAB)}
            >
              Installed
            </button>
            <button
              type="button"
              className={getSkillsTabClassName(MARKETPLACE_SKILLS_TAB, activeTab)}
              role="tab"
              aria-selected={activeTab === MARKETPLACE_SKILLS_TAB}
              onClick={() => handleTabChange(MARKETPLACE_SKILLS_TAB)}
            >
              Marketplace
            </button>
          </div>
        </Card.Body>
      </Card>

      {activeTab === MARKETPLACE_SKILLS_TAB ? (
        <SkillsMarketplacePanel />
      ) : (
        <LocalSkillsPanel
          detail={detail}
          detailError={detailError}
          detailLoading={detailLoading}
          editorContent={editorContent}
          filteredSkills={filtered}
          isSkillDirty={isSkillDirty}
          onDelete={() => setShowDeleteConfirm(true)}
          onEditorChange={setEditContent}
          onOpenMarketplace={handleOpenMarketplaceTab}
          onRefetchDetail={() => { void refetchDetail(); }}
          onSave={() => { void handleSave(); }}
          onSearchChange={setSearch}
          onSelectSkill={handleSelectAndLoad}
          searchQuery={search}
          selectedSkillName={selected}
          updatePending={updateMutation.isPending}
          deletePending={deleteMutation.isPending}
        />
      )}

      <Modal show={showCreate} onHide={() => setShowCreate(false)}>
        <Modal.Header closeButton>
          <Modal.Title>New Skill</Modal.Title>
        </Modal.Header>
        <Modal.Body>
          <Form.Group>
            <Form.Label>Skill name</Form.Label>
            <Form.Control
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  void handleCreate();
                }
              }}
              placeholder="my-skill"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              isInvalid={newNameInvalid || newNameExists}
              autoFocus
            />
            <Form.Text className={newNameInvalid || newNameExists ? 'text-danger' : 'text-body-secondary'}>
              {newNameInvalid
                ? 'Use lowercase letters, digits, and hyphens only (e.g. my-skill-name).'
                : newNameExists
                  ? 'A skill with this name already exists.'
                  : 'Use lowercase letters, digits, and hyphens only (e.g. my-skill-name).'}
            </Form.Text>
          </Form.Group>
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" variant="secondary" size="sm" onClick={() => setShowCreate(false)}>Cancel</Button>
          <Button type="button" size="sm" onClick={() => { void handleCreate(); }} disabled={!canCreate || createMutation.isPending}>
            {createMutation.isPending ? 'Creating...' : 'Create'}
          </Button>
        </Modal.Footer>
      </Modal>

      <ConfirmModal
        show={showDeleteConfirm}
        title="Delete Skill"
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
