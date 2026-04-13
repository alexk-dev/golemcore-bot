import { useMemo, type ReactElement } from 'react';
import { CodeEditor } from '../components/ide/CodeEditor';
import { EditorContentState, EditorStatusBar } from '../components/ide/EditorStatusBar';
import { EditorTabs } from '../components/ide/EditorTabs';
import { IdeFileExplorer, type IdeFileExplorerProps } from '../components/ide/IdeFileExplorer';
import { IdeHeader } from '../components/ide/IdeHeader';
import { QuickOpenModal } from '../components/ide/QuickOpenModal';
import { TreeActionModal } from '../components/ide/TreeActionModal';
import { UnsavedChangesModal } from '../components/ide/UnsavedChangesModal';
import { Offcanvas } from '../components/ui/overlay';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { useIdeMobileExplorer } from '../hooks/useIdeMobileExplorer';
import { useIdeWorkspace } from '../hooks/useIdeWorkspace';

export default function IdePage(): ReactElement {
  const ide = useIdeWorkspace();
  const {
    activePath, activeTab, activeColumn, activeFileSize, activeLanguage, activeLine, activeUpdatedAt,
    canSaveActiveTab, cancelCloseCandidate, cancelTreeAction, closeCandidate, closeCandidateLabel,
    closeCandidateWithoutSaving, closeQuickOpen, debouncedTreeSearchQuery, decreaseSidebarWidth,
    dirtyPaths, dirtyTabsCount, editorTabs, hasDirtyTabs, hasFileLoadError, increaseSidebarWidth,
    isCloseWithSavePending, isFileOpening, isQuickOpenVisible, isTreeActionPending, openFileFromQuickOpen,
    openQuickOpen, quickOpenItems, quickOpenQuery, refreshTree, requestCloseTab, requestCreateFromTree,
    requestDeleteFromTree, requestRenameFromTree, retryLoadContent, saveActiveTab, saveAndCloseCandidate,
    saveMutation, setActivePath, setEditorCursor, setTreeSearchQuery, sidebarWidth, startSidebarResize,
    submitCreateFromTree, submitDeleteFromTree, submitRenameFromTree, toggleQuickOpenPinned, treeAction,
    treeQuery, treeSearchQuery, updateActiveTabContent, updateQuickOpenQuery,
  } = ide;
  const isMobileLayout = useMediaQuery('(max-width: 991.98px)');
  const mobileExplorer = useIdeMobileExplorer(isMobileLayout);

  const activeFileLabel = useMemo(() => {
    if (activePath == null) {
      return null;
    }

    return editorTabs.find((tab) => tab.path === activePath)?.fullTitle ?? activePath;
  }, [activePath, editorTabs]);

  const handleOpenFile = useMemo(() => mobileExplorer.wrapAction(setActivePath), [mobileExplorer, setActivePath]);
  const handleRequestCreate = useMemo(
    () => mobileExplorer.wrapAction(requestCreateFromTree),
    [mobileExplorer, requestCreateFromTree],
  );
  const handleRequestRename = useMemo(
    () => mobileExplorer.wrapAction(requestRenameFromTree),
    [mobileExplorer, requestRenameFromTree],
  );
  const handleRequestDelete = useMemo(
    () => mobileExplorer.wrapAction(requestDeleteFromTree),
    [mobileExplorer, requestDeleteFromTree],
  );

  const explorerProps = useMemo<IdeFileExplorerProps>(() => ({
    nodes: treeQuery.data,
    isLoading: treeQuery.isLoading,
    isError: treeQuery.isError,
    isRefreshing: treeQuery.isFetching,
    selectedPath: activePath,
    dirtyPaths,
    searchInputValue: treeSearchQuery,
    searchQuery: debouncedTreeSearchQuery,
    onSearchQueryChange: setTreeSearchQuery,
    onRefresh: refreshTree,
    onCreateAtRoot: () => handleRequestCreate(''),
    onOpenFile: handleOpenFile,
    onRequestCreate: handleRequestCreate,
    onRequestRename: handleRequestRename,
    onRequestDelete: handleRequestDelete,
  }), [
    activePath,
    debouncedTreeSearchQuery,
    dirtyPaths,
    handleOpenFile,
    handleRequestCreate,
    handleRequestDelete,
    handleRequestRename,
    refreshTree,
    setTreeSearchQuery,
    treeQuery.data,
    treeQuery.isError,
    treeQuery.isFetching,
    treeQuery.isLoading,
    treeSearchQuery,
  ]);

  return (
    <div className="ide-page flex h-full flex-col">
      <IdeHeader
        activeFileLabel={activeFileLabel}
        isMobileLayout={isMobileLayout}
        hasDirtyTabs={hasDirtyTabs}
        dirtyTabsCount={dirtyTabsCount}
        canSaveActiveTab={canSaveActiveTab}
        isSaving={saveMutation.isPending}
        onSaveActiveTab={saveActiveTab}
        onOpenQuickOpen={openQuickOpen}
        onOpenExplorer={mobileExplorer.open}
        onIncreaseSidebarWidth={increaseSidebarWidth}
        onDecreaseSidebarWidth={decreaseSidebarWidth}
      />

      <div className="ide-layout flex min-h-0 flex-1 overflow-hidden">
        <section
          className="ide-sidebar-card hidden h-full rounded-[1.25rem] border border-border/80 bg-card/95 shadow-[0_18px_50px_rgba(15,23,42,0.08)] lg:block"
          aria-label="File explorer"
        >
          <IdeFileExplorer {...explorerProps} />
        </section>

        <div
          className="ide-sidebar-resizer hidden lg:block"
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize file explorer"
          aria-valuemin={240}
          aria-valuemax={520}
          aria-valuenow={sidebarWidth}
          onMouseDown={(event) => {
            event.preventDefault();
            startSidebarResize(event.clientX);
          }}
        />

        <section className="ide-editor-card h-full rounded-[1.25rem] border border-border/80 bg-card/95 shadow-[0_18px_50px_rgba(15,23,42,0.08)]">
          <div className="flex h-full flex-col overflow-hidden">
            <EditorTabs
              tabs={editorTabs}
              activePath={activePath}
              onSelectTab={setActivePath}
              onCloseTab={requestCloseTab}
            />
            <EditorStatusBar
              activePath={activeTab?.path ?? null}
              line={activeLine}
              column={activeColumn}
              language={activeLanguage}
              fileSizeBytes={activeFileSize}
              updatedAt={activeUpdatedAt}
            />

            <div className="flex-grow-1 overflow-hidden">
              <EditorContentState
                isFileOpening={isFileOpening}
                hasFileLoadError={hasFileLoadError}
                hasActiveTab={activeTab != null}
                onRetry={retryLoadContent}
              >
                <CodeEditor
                  filePath={activeTab?.path ?? null}
                  value={activeTab?.content ?? ''}
                  onChange={updateActiveTabContent}
                  onCursorChange={setEditorCursor}
                  showMinimap={!isMobileLayout}
                />
              </EditorContentState>
            </div>
          </div>
        </section>
      </div>

      <Offcanvas
        show={isMobileLayout && mobileExplorer.isOpen}
        onHide={mobileExplorer.close}
        placement="start"
        className="max-w-[92vw] border-l-0 border-r border-border/80"
      >
        <Offcanvas.Header closeButton>
          <Offcanvas.Title>Files</Offcanvas.Title>
        </Offcanvas.Header>
        <Offcanvas.Body className="p-0">
          <IdeFileExplorer {...explorerProps} />
        </Offcanvas.Body>
      </Offcanvas>

      <QuickOpenModal
        show={isQuickOpenVisible}
        query={quickOpenQuery}
        items={quickOpenItems}
        onClose={closeQuickOpen}
        onQueryChange={updateQuickOpenQuery}
        onPick={openFileFromQuickOpen}
        onTogglePinned={toggleQuickOpenPinned}
      />

      <UnsavedChangesModal
        show={closeCandidate != null}
        fileTitle={closeCandidateLabel}
        isProcessing={isCloseWithSavePending}
        onCancel={cancelCloseCandidate}
        onCloseWithoutSaving={closeCandidateWithoutSaving}
        onSaveAndClose={saveAndCloseCandidate}
      />

      <TreeActionModal
        action={treeAction}
        isProcessing={isTreeActionPending}
        onCancel={cancelTreeAction}
        onCreate={submitCreateFromTree}
        onRename={submitRenameFromTree}
        onDelete={submitDeleteFromTree}
      />
    </div>
  );
}
