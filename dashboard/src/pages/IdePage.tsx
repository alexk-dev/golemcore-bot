import { Suspense, useMemo, type ReactElement } from 'react';
import { EditorContentState, EditorStatusBar } from '../components/ide/EditorStatusBar';
import { EditorTabs } from '../components/ide/EditorTabs';
import { FilePreview } from '../components/ide/FilePreview';
import { IdeBreadcrumbs } from '../components/ide/IdeBreadcrumbs';
import { IdeCommandPalette } from '../components/ide/IdeCommandPalette';
import { IdeEditorSearchBar } from '../components/ide/IdeEditorSearchBar';
import { IdeEditorSettingsPanel } from '../components/ide/IdeEditorSettingsPanel';
import { IdeFileExplorer, type IdeFileExplorerProps } from '../components/ide/IdeFileExplorer';
import { IdeHeader } from '../components/ide/IdeHeader';
import { QuickOpenModal } from '../components/ide/QuickOpenModal';
import { TreeActionModal } from '../components/ide/TreeActionModal';
import { UnsavedChangesModal } from '../components/ide/UnsavedChangesModal';
import { Offcanvas } from '../components/ui/overlay';
import { useMediaQuery } from '../hooks/useMediaQuery';
import { useIdeMobileExplorer } from '../hooks/useIdeMobileExplorer';
import { useIdeWorkspace } from '../hooks/useIdeWorkspace';
import { LazyCodeEditor } from '../components/ide/LazyCodeEditor';
import '../styles/ide.scss';

function CodeEditorFallback(): ReactElement {
  return (
    <div className="flex h-full items-center justify-center gap-2 text-sm text-muted-foreground">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-border border-t-primary" role="status" aria-hidden />
      <span>Loading editor...</span>
    </div>
  );
}

export default function IdePage(): ReactElement {
  const ide = useIdeWorkspace();
  const {
    activePath, activeTab, activeColumn, activeFileSize, activeLanguage, activeLine, activeUpdatedAt,
    canSaveActiveTab, cancelCloseCandidate, cancelTreeAction, closeCandidate, closeCandidateLabel,
    closeCandidateWithoutSaving, closeCommandPalette, closeQuickOpen, debouncedTreeSearchQuery, decreaseSidebarWidth,
    dirtyPaths, dirtyTabsCount, downloadActiveFile, editorTabs, editorSearchQuery, editorSettings, hasDirtyTabs,
    hasFileLoadError, includeIgnored, increaseSidebarWidth, isCloseWithSavePending, isCommandPaletteVisible,
    isDownloadingActiveFile, isEditorSearchVisible, isEditorSettingsVisible, isFileOpening, isQuickOpenVisible,
    isTreeActionPending, loadDirectory, openFileFromQuickOpen, openQuickOpen, quickOpenItems, quickOpenQuery,
    refreshTree, requestCloseTab, requestCreateFromTree, requestDeleteFromTree, requestRenameFromTree,
    retryLoadContent, saveActiveTab, saveAndCloseCandidate, saveMutation, setActivePath, setEditorCursor,
    setEditorFontSize, setEditorSearchQuery, setEditorWordWrap, setTreeSearchQuery, sidebarWidth,
    startSidebarResize, submitCreateFromTree, submitDeleteFromTree, submitRenameFromTree, toggleEditorSearch,
    toggleEditorSettings, toggleIncludeIgnored, toggleQuickOpenPinned, treeAction, treeNodes, treeQuery,
    treeSearchQuery, updateActiveTabContent, updateQuickOpenQuery, uploadFiles,
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
    nodes: treeNodes,
    isLoading: treeQuery.isLoading,
    isError: treeQuery.isError,
    isRefreshing: treeQuery.isFetching,
    selectedPath: activePath,
    dirtyPaths,
    searchInputValue: treeSearchQuery,
    searchQuery: debouncedTreeSearchQuery,
    includeIgnored,
    isDownloadingActiveFile,
    onSearchQueryChange: setTreeSearchQuery,
    onRefresh: refreshTree,
    onCreateAtRoot: () => handleRequestCreate(''),
    onOpenFile: handleOpenFile,
    onLoadDirectory: loadDirectory,
    onRequestCreate: handleRequestCreate,
    onRequestRename: handleRequestRename,
    onRequestDelete: handleRequestDelete,
    onToggleIncludeIgnored: toggleIncludeIgnored,
    onDownloadActiveFile: downloadActiveFile,
    onUploadFiles: uploadFiles,
  }), [
    activePath,
    debouncedTreeSearchQuery,
    dirtyPaths,
    downloadActiveFile,
    handleOpenFile,
    handleRequestCreate,
    handleRequestDelete,
    handleRequestRename,
    includeIgnored,
    isDownloadingActiveFile,
    loadDirectory,
    refreshTree,
    setTreeSearchQuery,
    toggleIncludeIgnored,
    treeNodes,
    treeQuery.isError,
    treeQuery.isFetching,
    treeQuery.isLoading,
    treeSearchQuery,
    uploadFiles,
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
            <IdeBreadcrumbs path={activePath} onOpenPath={setActivePath} />
            <EditorStatusBar
              activePath={activeTab?.path ?? null}
              line={activeLine}
              column={activeColumn}
              language={activeLanguage}
              fileSizeBytes={activeFileSize}
              updatedAt={activeUpdatedAt}
            />
            <IdeEditorSearchBar
              show={isEditorSearchVisible}
              query={editorSearchQuery}
              onQueryChange={setEditorSearchQuery}
              onClose={toggleEditorSearch}
            />
            <IdeEditorSettingsPanel
              show={isEditorSettingsVisible}
              fontSize={editorSettings.fontSize}
              wordWrap={editorSettings.wordWrap}
              minimap={editorSettings.minimap}
              onFontSizeChange={setEditorFontSize}
              onWordWrapChange={setEditorWordWrap}
              onMinimapChange={editorSettings.setMinimap}
            />

            <div className="flex-grow-1 overflow-hidden">
              <EditorContentState
                isFileOpening={isFileOpening}
                hasFileLoadError={hasFileLoadError}
                hasActiveTab={activeTab != null}
                onRetry={retryLoadContent}
              >
                {activeTab != null && !activeTab.editable ? (
                  <FilePreview file={{
                    path: activeTab.path,
                    content: null,
                    size: activeFileSize,
                    updatedAt: activeUpdatedAt ?? '',
                    mimeType: activeTab.mimeType,
                    binary: activeTab.binary,
                    image: activeTab.image,
                    editable: activeTab.editable,
                    downloadUrl: activeTab.downloadUrl,
                  }} />
                ) : activeTab != null ? (
                  <Suspense fallback={<CodeEditorFallback />}>
                    <LazyCodeEditor
                      filePath={activeTab.path}
                      value={activeTab.content}
                      onChange={updateActiveTabContent}
                      onCursorChange={setEditorCursor}
                      showMinimap={!isMobileLayout && editorSettings.minimap}
                      wordWrap={editorSettings.wordWrap}
                      fontSize={editorSettings.fontSize}
                      searchQuery={editorSearchQuery}
                    />
                  </Suspense>
                ) : (
                  <></>
                )}
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

      <IdeCommandPalette
        show={isCommandPaletteVisible}
        canSaveActiveTab={canSaveActiveTab}
        hasActiveTab={activeTab != null}
        activePath={activePath}
        isDownloadingActiveFile={isDownloadingActiveFile}
        onClose={closeCommandPalette}
        onSaveActiveTab={saveActiveTab}
        onOpenQuickOpen={openQuickOpen}
        onToggleEditorSearch={toggleEditorSearch}
        onToggleSettings={toggleEditorSettings}
        onDownloadActiveFile={downloadActiveFile}
      />

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
