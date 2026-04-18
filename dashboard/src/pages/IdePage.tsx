import { useMemo, type MouseEventHandler, Suspense, type ReactElement } from 'react';
import { LazyCodeEditor } from '../components/ide/LazyCodeEditor';
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
import { useIdeMobileExplorer } from '../hooks/useIdeMobileExplorer';
import { useIdeWorkspace } from '../hooks/useIdeWorkspace';
import { useMediaQuery } from '../hooks/useMediaQuery';
import '../styles/ide.scss';
import { useTerminalStore } from '../store/terminalStore';
import { useWorkspaceLayoutStore } from '../store/workspaceLayoutStore';

interface ExplorerCallbacks {
  openFile: (path: string) => void;
  requestCreate: (targetPath: string) => void;
  requestRename: (targetPath: string) => void;
  requestDelete: (targetPath: string) => void;
  openTerminalHere: (path: string) => void;
}

interface IdeSidebarProps {
  explorerProps: IdeFileExplorerProps;
}

interface IdeEditorPaneProps {
  ide: ReturnType<typeof useIdeWorkspace>;
  isMobileLayout: boolean;
  onSidebarResizeStart: MouseEventHandler<HTMLDivElement>;
}

interface IdePageModalsProps {
  ide: ReturnType<typeof useIdeWorkspace>;
}

/**
 * Lightweight loading placeholder shown while the editor bundle is loaded on demand.
 */
function CodeEditorFallback(): ReactElement {
  return (
    <div className="flex h-full items-center justify-center gap-2 text-sm text-muted-foreground">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-border border-t-primary" role="status" aria-hidden />
      <span>Loading editor...</span>
    </div>
  );
}

function buildExplorerProps(
  ide: ReturnType<typeof useIdeWorkspace>,
  callbacks: ExplorerCallbacks,
): IdeFileExplorerProps {
  return {
    nodes: ide.treeNodes,
    isLoading: ide.treeQuery.isLoading,
    isError: ide.treeQuery.isError,
    isRefreshing: ide.treeQuery.isFetching,
    selectedPath: ide.activePath,
    dirtyPaths: ide.dirtyPaths,
    searchInputValue: ide.treeSearchQuery,
    searchQuery: ide.debouncedTreeSearchQuery,
    includeIgnored: ide.includeIgnored,
    isDownloadingActiveFile: ide.isDownloadingActiveFile,
    onSearchQueryChange: ide.setTreeSearchQuery,
    onRefresh: ide.refreshTree,
    onCreateAtRoot: () => callbacks.requestCreate(''),
    onOpenFile: callbacks.openFile,
    onLoadDirectory: ide.loadDirectory,
    onRequestCreate: callbacks.requestCreate,
    onRequestRename: callbacks.requestRename,
    onRequestDelete: callbacks.requestDelete,
    onOpenTerminalHere: callbacks.openTerminalHere,
    onToggleIncludeIgnored: ide.toggleIncludeIgnored,
    onDownloadActiveFile: ide.downloadActiveFile,
    onUploadFiles: ide.uploadFiles,
  };
}

/**
 * Wraps explorer callbacks so mobile interactions close the drawer after the
 * action succeeds.
 */
function useExplorerCallbacks(
  mobileExplorer: ReturnType<typeof useIdeMobileExplorer>,
  ide: ReturnType<typeof useIdeWorkspace>,
  openTerminalTab: (cwd?: string) => string,
  setTerminalVisible: (visible: boolean) => void,
): ExplorerCallbacks {
  const openFile = useMemo(() => mobileExplorer.wrapAction(ide.setActivePath), [ide.setActivePath, mobileExplorer]);
  const requestCreate = useMemo(
    () => mobileExplorer.wrapAction(ide.requestCreateFromTree),
    [ide.requestCreateFromTree, mobileExplorer],
  );
  const requestRename = useMemo(
    () => mobileExplorer.wrapAction(ide.requestRenameFromTree),
    [ide.requestRenameFromTree, mobileExplorer],
  );
  const requestDelete = useMemo(
    () => mobileExplorer.wrapAction(ide.requestDeleteFromTree),
    [ide.requestDeleteFromTree, mobileExplorer],
  );
  const openTerminalHere = useMemo(
    () => mobileExplorer.wrapAction((path: string): void => {
      openTerminalTab(path);
      setTerminalVisible(true);
    }),
    [mobileExplorer, openTerminalTab, setTerminalVisible],
  );

  return {
    openFile,
    requestCreate,
    requestRename,
    requestDelete,
    openTerminalHere,
  };
}

function IdeSidebar({ explorerProps }: IdeSidebarProps): ReactElement {
  return (
    <section
      className="ide-sidebar-card hidden h-full rounded-[1.25rem] border border-border/80 bg-card/95 shadow-[0_18px_50px_rgba(15,23,42,0.08)] lg:block"
      aria-label="File explorer"
    >
      <IdeFileExplorer {...explorerProps} />
    </section>
  );
}

function IdeEditorPane({ ide, isMobileLayout, onSidebarResizeStart }: IdeEditorPaneProps): ReactElement {
  return (
    <>
      <div
        className="ide-sidebar-resizer hidden lg:block"
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize file explorer"
        aria-valuemin={240}
        aria-valuemax={520}
        aria-valuenow={ide.sidebarWidth}
        onMouseDown={onSidebarResizeStart}
      />

      <section className="ide-editor-card h-full rounded-[1.25rem] border border-border/80 bg-card/95 shadow-[0_18px_50px_rgba(15,23,42,0.08)]">
        <div className="flex h-full min-h-0 flex-col overflow-hidden">
          <EditorTabs
            tabs={ide.editorTabs}
            activePath={ide.activePath}
            onSelectTab={ide.setActivePath}
            onCloseTab={ide.requestCloseTab}
          />
          <IdeBreadcrumbs path={ide.activePath} onOpenPath={ide.setActivePath} />
          <EditorStatusBar
            activePath={ide.activeTab?.path ?? null}
            line={ide.activeLine}
            column={ide.activeColumn}
            language={ide.activeLanguage}
            fileSizeBytes={ide.activeFileSize}
            updatedAt={ide.activeUpdatedAt ?? ''}
          />
          <IdeEditorSearchBar
            show={ide.isEditorSearchVisible}
            query={ide.editorSearchQuery}
            onQueryChange={ide.setEditorSearchQuery}
            onClose={ide.toggleEditorSearch}
          />
          <IdeEditorSettingsPanel
            show={ide.isEditorSettingsVisible}
            fontSize={ide.editorSettings.fontSize}
            wordWrap={ide.editorSettings.wordWrap}
            minimap={ide.editorSettings.minimap}
            onFontSizeChange={ide.setEditorFontSize}
            onWordWrapChange={ide.setEditorWordWrap}
            onMinimapChange={ide.editorSettings.setMinimap}
          />

          <div className="min-h-0 flex-1 overflow-hidden">
            <EditorContentState
              isFileOpening={ide.isFileOpening}
              hasFileLoadError={ide.hasFileLoadError}
              hasActiveTab={ide.activeTab != null}
              onRetry={ide.retryLoadContent}
            >
              {ide.activeTab != null && !ide.activeTab.editable ? (
                <FilePreview file={{
                  path: ide.activeTab.path,
                  content: null,
                  size: ide.activeFileSize,
                  updatedAt: ide.activeUpdatedAt ?? '',
                  mimeType: ide.activeTab.mimeType,
                  binary: ide.activeTab.binary,
                  image: ide.activeTab.image,
                  editable: ide.activeTab.editable,
                  downloadUrl: ide.activeTab.downloadUrl,
                }} />
              ) : ide.activeTab != null ? (
                <Suspense fallback={<CodeEditorFallback />}>
                  <LazyCodeEditor
                    filePath={ide.activeTab.path}
                    value={ide.activeTab.content}
                    onChange={ide.updateActiveTabContent}
                    onCursorChange={ide.setEditorCursor}
                    showMinimap={!isMobileLayout && ide.editorSettings.minimap}
                    wordWrap={ide.editorSettings.wordWrap}
                    fontSize={ide.editorSettings.fontSize}
                    searchQuery={ide.editorSearchQuery}
                  />
                </Suspense>
              ) : (
                <></>
              )}
            </EditorContentState>
          </div>
        </div>
      </section>
    </>
  );
}

function IdePageModals({ ide }: IdePageModalsProps): ReactElement {
  return (
    <>
      <IdeCommandPalette
        show={ide.isCommandPaletteVisible}
        canSaveActiveTab={ide.canSaveActiveTab}
        hasActiveTab={ide.activeTab != null}
        activePath={ide.activePath}
        isDownloadingActiveFile={ide.isDownloadingActiveFile}
        onClose={ide.closeCommandPalette}
        onSaveActiveTab={ide.saveActiveTab}
        onOpenQuickOpen={ide.openQuickOpen}
        onToggleEditorSearch={ide.toggleEditorSearch}
        onToggleSettings={ide.toggleEditorSettings}
        onDownloadActiveFile={ide.downloadActiveFile}
      />

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
    </>
  );
}

function handleSidebarResizeStartFactory(
  startSidebarResize: (clientX: number) => void,
): MouseEventHandler<HTMLDivElement> {
  return (event) => {
    event.preventDefault();
    startSidebarResize(event.clientX);
  };
}

/**
 * Renders the IDE route used inside the workspace shell, including the file tree,
 * editor surface, and supporting modals.
 */
export default function IdePage(): ReactElement {
  const ide = useIdeWorkspace();
  const openTerminalTab = useTerminalStore((state) => state.openTab);
  const setTerminalVisible = useWorkspaceLayoutStore((state) => state.setTerminalVisible);
  const isMobileLayout = useMediaQuery('(max-width: 991.98px)');
  const mobileExplorer = useIdeMobileExplorer(isMobileLayout);
  const callbacks = useExplorerCallbacks(mobileExplorer, ide, openTerminalTab, setTerminalVisible);

  const activeFileLabel = useMemo(() => {
    if (ide.activePath == null) {
      return null;
    }

    return ide.editorTabs.find((tab) => tab.path === ide.activePath)?.fullTitle ?? ide.activePath;
  }, [ide.activePath, ide.editorTabs]);
  const explorerProps = useMemo(() => buildExplorerProps(ide, callbacks), [callbacks, ide]);
  const handleSidebarResizeStart = useMemo(
    () => handleSidebarResizeStartFactory(ide.startSidebarResize),
    [ide.startSidebarResize],
  );

  return (
    <div className="ide-page flex h-full min-h-0 flex-col overflow-hidden">
      <IdeHeader
        activeFileLabel={activeFileLabel}
        isMobileLayout={isMobileLayout}
        hasDirtyTabs={ide.hasDirtyTabs}
        dirtyTabsCount={ide.dirtyTabsCount}
        canSaveActiveTab={ide.canSaveActiveTab}
        isSaving={ide.saveMutation.isPending}
        onSaveActiveTab={ide.saveActiveTab}
        onOpenQuickOpen={ide.openQuickOpen}
        onOpenExplorer={mobileExplorer.open}
        onIncreaseSidebarWidth={ide.increaseSidebarWidth}
        onDecreaseSidebarWidth={ide.decreaseSidebarWidth}
      />

      <div className="ide-layout flex min-h-0 flex-1 overflow-hidden">
        <IdeSidebar explorerProps={explorerProps} />
        <IdeEditorPane
          ide={ide}
          isMobileLayout={isMobileLayout}
          onSidebarResizeStart={handleSidebarResizeStart}
        />
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

      <IdePageModals ide={ide} />
    </div>
  );
}
