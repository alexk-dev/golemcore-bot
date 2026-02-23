import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, Spinner } from 'react-bootstrap';
import toast from 'react-hot-toast';
import { FiRefreshCw, FiSave } from 'react-icons/fi';
import { useFileContent, useFileTree, useSaveFileContent } from '../hooks/useFiles';
import { CodeEditor } from '../components/ide/CodeEditor';
import { EditorTabs } from '../components/ide/EditorTabs';
import { FileTreePanel } from '../components/ide/FileTreePanel';
import ConfirmModal from '../components/common/ConfirmModal';
import { createNewTab, useIdeStore } from '../store/ideStore';

export default function IdePage(): ReactElement {
  const [closeCandidatePath, setCloseCandidatePath] = useState<string | null>(null);
  const openedTabs = useIdeStore((state) => state.openedTabs);
  const activePath = useIdeStore((state) => state.activePath);
  const setActivePath = useIdeStore((state) => state.setActivePath);
  const upsertTab = useIdeStore((state) => state.upsertTab);
  const closeTab = useIdeStore((state) => state.closeTab);
  const updateTabContent = useIdeStore((state) => state.updateTabContent);
  const markSaved = useIdeStore((state) => state.markSaved);

  const treeQuery = useFileTree('');
  const contentQuery = useFileContent(activePath ?? '');
  const saveMutation = useSaveFileContent();

  const activeTab = useMemo(() => {
    if (activePath == null) {
      return null;
    }
    return openedTabs.find((tab) => tab.path === activePath) ?? null;
  }, [activePath, openedTabs]);

  useEffect(() => {
    // Synchronize loaded API content into tab state when file becomes active.
    const payload = contentQuery.data;
    if (payload == null) {
      return;
    }

    const existing = openedTabs.find((tab) => tab.path === payload.path);
    if (existing == null) {
      upsertTab(createNewTab(payload.path, payload.content));
      return;
    }

    if (!existing.isDirty && existing.savedContent !== payload.content) {
      upsertTab({
        ...existing,
        content: payload.content,
        savedContent: payload.content,
        isDirty: false,
      });
    }
  }, [contentQuery.data, openedTabs, upsertTab]);

  useEffect(() => {
    // Register global save shortcut for editor workflow.
    const handler = (event: KeyboardEvent): void => {
      const isSaveCombo = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's';
      if (!isSaveCombo) {
        return;
      }
      event.preventDefault();
      if (activeTab == null) {
        return;
      }
      void saveMutation.mutateAsync({ path: activeTab.path, content: activeTab.content })
        .then((saved) => {
          markSaved(saved.path, saved.content);
          toast.success(`Saved ${saved.path}`);
        })
        .catch(() => {
          toast.error('Failed to save file');
        });
    };

    window.addEventListener('keydown', handler);
    return () => {
      window.removeEventListener('keydown', handler);
    };
  }, [activeTab, markSaved, saveMutation]);

  const handleOpenFile = (path: string): void => {
    setActivePath(path);
  };

  const handleSave = async (): Promise<void> => {
    if (activeTab == null) {
      return;
    }

    try {
      const saved = await saveMutation.mutateAsync({ path: activeTab.path, content: activeTab.content });
      markSaved(saved.path, saved.content);
      toast.success(`Saved ${saved.path}`);
    } catch {
      toast.error('Failed to save file');
    }
  };

  const handleCloseTab = (path: string): void => {
    const tab = openedTabs.find((candidate) => candidate.path === path);
    if (tab == null) {
      return;
    }

    if (tab.isDirty) {
      setCloseCandidatePath(path);
      return;
    }

    closeTab(path);
  };

  const closeCandidate = closeCandidatePath == null
    ? null
    : openedTabs.find((tab) => tab.path === closeCandidatePath) ?? null;

  return (
    <div className="ide-page h-100 d-flex flex-column">
      <div className="section-header d-flex align-items-center justify-content-between">
        <h4 className="mb-0">IDE</h4>
        <div className="d-flex align-items-center gap-2">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => {
              void treeQuery.refetch();
            }}
            disabled={treeQuery.isFetching}
          >
            <FiRefreshCw size={14} className="me-1" />
            {treeQuery.isFetching ? 'Refreshing...' : 'Refresh'}
          </Button>
          <Button
            size="sm"
            onClick={() => {
              void handleSave();
            }}
            disabled={activeTab == null || saveMutation.isPending}
          >
            <FiSave size={14} className="me-1" />
            {saveMutation.isPending ? 'Saving...' : 'Save'}
          </Button>
        </div>
      </div>

      <div className="ide-layout flex-grow-1 d-flex overflow-hidden">
        <Card className="ide-sidebar-card h-100">
          <Card.Body className="p-2 h-100">
            {treeQuery.isLoading ? (
              <div className="d-flex align-items-center justify-content-center h-100">
                <Spinner animation="border" size="sm" />
              </div>
            ) : treeQuery.isError ? (
              <Alert variant="danger" className="mb-0 small">
                Failed to load file tree.
              </Alert>
            ) : (
              <FileTreePanel
                nodes={treeQuery.data ?? []}
                selectedPath={activePath}
                onOpenFile={handleOpenFile}
              />
            )}
          </Card.Body>
        </Card>

        <Card className="ide-editor-card h-100">
          <Card.Body className="p-0 d-flex flex-column h-100 overflow-hidden">
            <EditorTabs
              tabs={openedTabs.map((tab) => ({
                path: tab.path,
                title: tab.title,
                dirty: tab.isDirty,
              }))}
              activePath={activePath}
              onSelectTab={setActivePath}
              onCloseTab={handleCloseTab}
            />

            <div className="flex-grow-1 overflow-hidden">
              {activeTab == null ? (
                <div className="h-100 d-flex align-items-center justify-content-center text-body-secondary">
                  Open a file from the tree to start editing.
                </div>
              ) : contentQuery.isLoading && activeTab.content.length === 0 ? (
                <div className="h-100 d-flex align-items-center justify-content-center">
                  <Spinner animation="border" size="sm" />
                </div>
              ) : contentQuery.isError ? (
                <Alert variant="danger" className="m-3">Failed to load file content.</Alert>
              ) : (
                <CodeEditor
                  filePath={activeTab.path}
                  value={activeTab.content}
                  onChange={(nextValue: string) => {
                    updateTabContent(activeTab.path, nextValue);
                  }}
                />
              )}
            </div>
          </Card.Body>
        </Card>
      </div>

      <ConfirmModal
        show={closeCandidate != null}
        title="Close unsaved tab"
        message={closeCandidate != null
          ? `File "${closeCandidate.title}" has unsaved changes. Close without saving?`
          : ''}
        confirmLabel="Close tab"
        confirmVariant="danger"
        onConfirm={() => {
          if (closeCandidatePath != null) {
            closeTab(closeCandidatePath);
          }
          setCloseCandidatePath(null);
        }}
        onCancel={() => setCloseCandidatePath(null)}
      />
    </div>
  );
}
