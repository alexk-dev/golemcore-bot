import type { ReactElement } from 'react';
import { Alert, Card, Spinner } from 'react-bootstrap';
import { CodeEditor } from '../components/ide/CodeEditor';
import { EditorContentState, EditorStatusBar } from '../components/ide/EditorStatusBar';
import { EditorTabs } from '../components/ide/EditorTabs';
import { FileTreePanel } from '../components/ide/FileTreePanel';
import { IdeHeader } from '../components/ide/IdeHeader';
import { UnsavedChangesModal } from '../components/ide/UnsavedChangesModal';
import { useIdeWorkspace } from '../hooks/useIdeWorkspace';

export default function IdePage(): ReactElement {
  const ide = useIdeWorkspace();

  return (
    <div className="ide-page h-100 d-flex flex-column">
      <IdeHeader
        hasDirtyTabs={ide.hasDirtyTabs}
        dirtyTabsCount={ide.dirtyTabsCount}
        isRefreshingTree={ide.treeQuery.isFetching}
        canSaveActiveTab={ide.canSaveActiveTab}
        isSaving={ide.saveMutation.isPending}
        onRefreshTree={ide.refreshTree}
        onSaveActiveTab={ide.saveActiveTab}
      />

      <div className="ide-layout flex-grow-1 d-flex overflow-hidden">
        <Card className="ide-sidebar-card h-100" aria-label="File explorer">
          <Card.Body className="p-2 h-100">
            {ide.treeQuery.isLoading ? (
              <div className="d-flex align-items-center justify-content-center h-100">
                <Spinner animation="border" size="sm" />
              </div>
            ) : ide.treeQuery.isError ? (
              <Alert variant="danger" className="mb-0 small">Failed to load file tree.</Alert>
            ) : (
              <FileTreePanel
                nodes={ide.treeQuery.data ?? []}
                selectedPath={ide.activePath}
                onOpenFile={ide.setActivePath}
              />
            )}
          </Card.Body>
        </Card>

        <Card className="ide-editor-card h-100">
          <Card.Body className="p-0 d-flex flex-column h-100 overflow-hidden">
            <EditorTabs
              tabs={ide.openedTabs.map((tab) => ({ path: tab.path, title: tab.title, dirty: tab.isDirty }))}
              activePath={ide.activePath}
              onSelectTab={ide.setActivePath}
              onCloseTab={ide.requestCloseTab}
            />
            <EditorStatusBar activePath={ide.activeTab?.path ?? null} />

            <div className="flex-grow-1 overflow-hidden">
              <EditorContentState
                isFileOpening={ide.isFileOpening}
                hasFileLoadError={ide.hasFileLoadError}
                hasActiveTab={ide.activeTab != null}
                onRetry={ide.retryLoadContent}
              >
                <CodeEditor
                  filePath={ide.activeTab?.path ?? null}
                  value={ide.activeTab?.content ?? ''}
                  onChange={ide.updateActiveTabContent}
                />
              </EditorContentState>
            </div>
          </Card.Body>
        </Card>
      </div>

      <UnsavedChangesModal
        show={ide.closeCandidate != null}
        fileTitle={ide.closeCandidate?.title ?? ''}
        isProcessing={ide.isCloseWithSavePending}
        onCancel={ide.cancelCloseCandidate}
        onCloseWithoutSaving={ide.closeCandidateWithoutSaving}
        onSaveAndClose={ide.saveAndCloseCandidate}
      />
    </div>
  );
}
