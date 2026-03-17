import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { FiCpu, FiRefreshCw } from 'react-icons/fi';
import type { SkillInfo, SkillUpdateRequest } from '../../api/skills';
import { Alert } from '../../components/ui/alert';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { buildSkillUpdateRequest, createSkillEditorDraft, serializeSkillUpdateRequest, type SkillEditorDraft } from './skillEditorDraft';
import { LoadingState } from './LocalSkillsPanelShared';
import {
  SkillEditorActions,
  SkillIdentitySection,
  SkillInstructionsSection,
  SkillMcpSection,
  SkillRequirementsSection,
  SkillSequencesSection,
  SkillVariablesSection,
} from './LocalSkillDetailSections';

interface LocalSkillDetailPaneProps {
  detail: SkillInfo | undefined;
  selectedSkillName: string | null;
  detailLoading: boolean;
  detailError: boolean;
  onRefetchDetail: () => void;
  updatePending: boolean;
  onSave: (request: SkillUpdateRequest) => Promise<SkillInfo>;
  deletePending: boolean;
  onDelete: () => void;
}

function hasSelectedSkill(selectedSkillName: string | null): boolean {
  return selectedSkillName != null && selectedSkillName.length > 0;
}

function shouldInitializeDraft(
  detail: SkillInfo | undefined,
  selectedSkillName: string | null,
  initializedFor: string | null,
): detail is SkillInfo {
  return detail != null
    && selectedSkillName != null
    && detail.name === selectedSkillName
    && initializedFor !== selectedSkillName;
}

export function LocalSkillDetailPane({
  detail,
  selectedSkillName,
  detailLoading,
  detailError,
  onRefetchDetail,
  updatePending,
  onSave,
  deletePending,
  onDelete,
}: LocalSkillDetailPaneProps): ReactElement {
  const [draft, setDraft] = useState<SkillEditorDraft | null>(null);
  const [initializedFor, setInitializedFor] = useState<string | null>(null);
  const [initialRequestSnapshot, setInitialRequestSnapshot] = useState('');
  const selected = hasSelectedSkill(selectedSkillName);

  useEffect(() => {
    if (selectedSkillName == null) {
      setDraft(null);
      setInitializedFor(null);
      setInitialRequestSnapshot('');
    }
  }, [selectedSkillName]);

  useEffect(() => {
    if (!shouldInitializeDraft(detail, selectedSkillName, initializedFor)) {
      return;
    }
    const nextDraft = createSkillEditorDraft(detail);
    const snapshot = serializeSkillUpdateRequest(buildSkillUpdateRequest(nextDraft));
    setDraft(nextDraft);
    setInitializedFor(selectedSkillName);
    setInitialRequestSnapshot(snapshot);
  }, [detail, initializedFor, selectedSkillName]);

  const currentRequest = useMemo(
    () => (draft != null ? buildSkillUpdateRequest(draft) : null),
    [draft],
  );
  const currentSnapshot = useMemo(
    () => (currentRequest != null ? serializeSkillUpdateRequest(currentRequest) : ''),
    [currentRequest],
  );
  const isDirty = draft != null && currentSnapshot !== initialRequestSnapshot;

  const updateDraft = (updater: (current: SkillEditorDraft) => SkillEditorDraft): void => {
    setDraft((current) => (current != null ? updater(current) : current));
  };

  const saveDraft = async (): Promise<void> => {
    if (currentRequest == null || !isDirty) {
      return;
    }
    const updated = await onSave(currentRequest);
    const nextDraft = createSkillEditorDraft(updated);
    const nextSnapshot = serializeSkillUpdateRequest(buildSkillUpdateRequest(nextDraft));
    setDraft(nextDraft);
    setInitializedFor(updated.name);
    setInitialRequestSnapshot(nextSnapshot);
  };

  if (selected && detailLoading) {
    return <LoadingState message="Loading skill..." />;
  }

  if (selected && detailError) {
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

  if (!selected || detail == null || draft == null) {
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
                Pick a local skill from the list to inspect metadata and update the full `SKILL.md`.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="min-h-[28rem]">
      <CardHeader className="items-start">
        <div className="space-y-2">
          <CardTitle className="text-lg">{detail.name}</CardTitle>
          <CardDescription>
            Edit supported `SKILL.md` metadata fields and the markdown body from one workspace.
          </CardDescription>
        </div>
        <div className="flex flex-wrap justify-end gap-1">
          {detail.hasMcp && <Badge variant="info">MCP</Badge>}
          {detail.modelTier != null && detail.modelTier.length > 0 && (
            <Badge variant="secondary">{detail.modelTier}</Badge>
          )}
          {!detail.available && <Badge variant="warning">Unavailable</Badge>}
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <SkillIdentitySection draft={draft} updateDraft={updateDraft} />
        <div className="grid gap-4 xl:grid-cols-2">
          <SkillRequirementsSection draft={draft} updateDraft={updateDraft} />
          <SkillSequencesSection draft={draft} updateDraft={updateDraft} />
        </div>
        <SkillVariablesSection draft={draft} detail={detail} updateDraft={updateDraft} />
        <SkillMcpSection draft={draft} updateDraft={updateDraft} />
        <SkillInstructionsSection draft={draft} updateDraft={updateDraft} />
        <SkillEditorActions
          isDirty={isDirty}
          updatePending={updatePending}
          deletePending={deletePending}
          onSave={() => { void saveDraft(); }}
          onDelete={onDelete}
        />
      </CardContent>
    </Card>
  );
}
