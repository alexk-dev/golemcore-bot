import type { ReactElement } from 'react';
import { CodeEditor } from '../components/ide/CodeEditor';
import { EditorContentState, EditorStatusBar } from '../components/ide/EditorStatusBar';
import { EditorTabs } from '../components/ide/EditorTabs';
import { FileTreePanel } from '../components/ide/FileTreePanel';
import { IdeHeader } from '../components/ide/IdeHeader';
import { QuickOpenModal } from '../components/ide/QuickOpenModal';
import { TreeActionModal } from '../components/ide/TreeActionModal';
import { UnsavedChangesModal } from '../components/ide/UnsavedChangesModal';
import { useIdeWorkspace } from '../hooks/useIdeWorkspace';

export default function IdePage(): ReactElement {
  const ide = useIdeWorkspace();

  return (
    <div className="ide-page flex h-full flex-col">
      <IdeHeader
        hasDirtyTabs={ide.hasDirtyTabs}
        dirtyTabsCount={ide.dirtyTabsCount}
        isRefreshingTree={ide.treeQuery.isFetching}
        canSaveActiveTab={ide.canSaveActiveTab}
        isSaving={ide.saveMutation.isPending}
        treeSearchQuery={ide.treeSearchQuery}
        onTreeSearchQueryChange={ide.setTreeSearchQuery}
        onRefreshTree={ide.refreshTree}
        onSaveActiveTab={ide.saveActiveTab}
        onOpenQuickOpen={ide.openQuickOpen}
        onIncreaseSidebarWidth={ide.increaseSidebarWidth}
        onDecreaseSidebarWidth={ide.decreaseSidebarWidth}
      />

      <div className="ide-layout flex min-h-0 flex-1 overflow-hidden">
        <section
          className="ide-sidebar-card h-full rounded-[1.25rem] border border-border/80 bg-card/95 shadow-[0_18px_50px_rgba(15,23,42,0.08)]"
          aria-label="File explorer"
        >
          <div className="h-full p-2">
            {ide.treeQuery.isLoading ? (
              <div className="flex h-full items-center justify-center">
                <span
                  className="h-5 w-5 animate-spin rounded-full border-2 border-border border-t-primary"
                  role="status"
                  aria-label="Loading file tree"
                />
              </div>
            ) : ide.treeQuery.isError ? (
              <div
                className="rounded-2xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive"
                role="alert"
              >
                Failed to load file tree.
              </div>
            ) : (
              <FileTreePanel
                nodes={ide.treeQuery.data ?? []}
                selectedPath={ide.activePath}
                dirtyPaths={ide.dirtyPaths}
                searchQuery={ide.debouncedTreeSearchQuery}
                onOpenFile={ide.setActivePath}
                onRequestCreate={ide.requestCreateFromTree}
                onRequestRename={ide.requestRenameFromTree}
                onRequestDelete={ide.requestDeleteFromTree}
              />
            )}
          </div>
        </section>

        <div
          className="ide-sidebar-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize file explorer"
          aria-valuemin={240}
          aria-valuemax={520}
          aria-valuenow={ide.sidebarWidth}
          onMouseDown={(event) => {
            event.preventDefault();
            ide.startSidebarResize(event.clientX);
          }}
        />

        <section className="ide-editor-card h-full rounded-[1.25rem] border border-border/80 bg-card/95 shadow-[0_18px_50px_rgba(15,23,42,0.08)]">
          <div className="flex h-full flex-col overflow-hidden">
            <EditorTabs
              tabs={ide.editorTabs}
              activePath={ide.activePath}
              onSelectTab={ide.setActivePath}
              onCloseTab={ide.requestCloseTab}
            />
            <EditorStatusBar
              activePath={ide.activeTab?.path ?? null}
              line={ide.activeLine}
              column={ide.activeColumn}
              language={ide.activeLanguage}
              fileSizeBytes={ide.activeFileSize}
              updatedAt={ide.activeUpdatedAt}
            />

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
                  onCursorChange={ide.setEditorCursor}
                  showMinimap
                />
              </EditorContentState>
            </div>
          </div>
        </section>
      </div>

      <QuickOpenModal
        show={ide.isQuickOpenVisible}
        query={ide.quickOpenQuery}
        items={ide.quickOpenItems}
        onClose={ide.closeQuickOpen}
        onQueryChange={ide.updateQuickOpenQuery}
        onPick={ide.openFileFromQuickOpen}
        onTogglePinned={ide.toggleQuickOpenPinned}
      />

      <UnsavedChangesModal
        show={ide.closeCandidate != null}
        fileTitle={ide.closeCandidateLabel}
        isProcessing={ide.isCloseWithSavePending}
        onCancel={ide.cancelCloseCandidate}
        onCloseWithoutSaving={ide.closeCandidateWithoutSaving}
        onSaveAndClose={ide.saveAndCloseCandidate}
      />

      <TreeActionModal
        action={ide.treeAction}
        isProcessing={ide.isTreeActionPending}
        onCancel={ide.cancelTreeAction}
        onCreate={ide.submitCreateFromTree}
        onRename={ide.submitRenameFromTree}
        onDelete={ide.submitDeleteFromTree}
      />
    </div>
  );
}
