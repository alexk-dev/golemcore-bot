import type { ReactElement } from 'react';
import { FiCommand, FiDownload, FiFileText, FiSave, FiSearch, FiSettings } from 'react-icons/fi';
import { Button } from '../ui/button';
import { Modal } from '../ui/overlay';

export interface IdeCommandPaletteProps {
  show: boolean;
  canSaveActiveTab: boolean;
  hasActiveTab: boolean;
  activeDownloadUrl: string | null;
  onClose: () => void;
  onSaveActiveTab: () => void;
  onOpenQuickOpen: () => void;
  onToggleEditorSearch: () => void;
  onToggleSettings: () => void;
}

export function IdeCommandPalette({
  show,
  canSaveActiveTab,
  hasActiveTab,
  activeDownloadUrl,
  onClose,
  onSaveActiveTab,
  onOpenQuickOpen,
  onToggleEditorSearch,
  onToggleSettings,
}: IdeCommandPaletteProps): ReactElement {
  const handleSave = (): void => {
    onSaveActiveTab();
    onClose();
  };

  const handleQuickOpen = (): void => {
    onOpenQuickOpen();
    onClose();
  };

  const handleSearch = (): void => {
    onToggleEditorSearch();
    onClose();
  };

  const handleSettings = (): void => {
    onToggleSettings();
    onClose();
  };

  return (
    <Modal show={show} onHide={onClose} centered size="lg">
      <Modal.Header closeButton>
        <Modal.Title>Command Palette</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <div className="grid gap-2">
          <Button variant="secondary" className="justify-start" onClick={handleQuickOpen}>
            <FiCommand size={14} />
            Quick Open
          </Button>
          <Button variant="secondary" className="justify-start" onClick={handleSave} disabled={!canSaveActiveTab}>
            <FiSave size={14} />
            Save Active File
          </Button>
          <Button variant="secondary" className="justify-start" onClick={handleSearch} disabled={!hasActiveTab}>
            <FiSearch size={14} />
            Toggle Editor Search
          </Button>
          <Button variant="secondary" className="justify-start" onClick={handleSettings}>
            <FiSettings size={14} />
            Toggle Editor Settings
          </Button>
          {activeDownloadUrl != null && (
            <a href={activeDownloadUrl} download className="no-underline">
              <Button variant="secondary" className="w-full justify-start">
                <FiDownload size={14} />
                Download Active File
              </Button>
            </a>
          )}
          {!hasActiveTab && (
            <div className="flex items-center gap-2 rounded-2xl border border-border/80 bg-muted/30 px-4 py-3 text-sm text-muted-foreground">
              <FiFileText size={14} />
              Open a file to enable editor commands.
            </div>
          )}
        </div>
      </Modal.Body>
    </Modal>
  );
}
