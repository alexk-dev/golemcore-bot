import { useEffect, useState, type ReactElement } from 'react';
import { Button, Modal } from 'react-bootstrap';
import { useNavigate } from 'react-router-dom';
import ChatWindow from '../components/chat/ChatWindow';
import { useRuntimeConfig } from '../hooks/useSettings';
import {
  dismissStartupSetupInviteForSession,
  isStartupSetupComplete,
  isStartupSetupInviteDismissed,
} from '../utils/startupSetup';

export default function ChatPage(): ReactElement {
  const navigate = useNavigate();
  const runtimeConfigQuery = useRuntimeConfig();
  const [isSetupInviteVisible, setIsSetupInviteVisible] = useState(false);

  useEffect(() => {
    // Show setup invite once per browser session when chat opens without required LLM setup.
    if (runtimeConfigQuery.isLoading || runtimeConfigQuery.data == null) {
      return;
    }
    if (isStartupSetupComplete(runtimeConfigQuery.data) || isStartupSetupInviteDismissed()) {
      return;
    }
    setIsSetupInviteVisible(true);
  }, [runtimeConfigQuery.data, runtimeConfigQuery.isLoading]);

  const handleCloseSetupInvite = (): void => {
    dismissStartupSetupInviteForSession();
    setIsSetupInviteVisible(false);
  };

  const handleOpenSetupWizard = (): void => {
    setIsSetupInviteVisible(false);
    navigate('/setup');
  };

  return (
    <>
      <ChatWindow />
      <Modal show={isSetupInviteVisible} onHide={handleCloseSetupInvite} centered>
        <Modal.Header closeButton>
          <Modal.Title>Complete Startup Setup?</Modal.Title>
        </Modal.Header>
        <Modal.Body className="text-body-secondary">
          You can chat now, but LLM provider and model routing are not fully configured yet.
          Open setup wizard to finish the recommended startup configuration.
        </Modal.Body>
        <Modal.Footer>
          <Button type="button" variant="secondary" onClick={handleCloseSetupInvite}>
            Later
          </Button>
          <Button type="button" variant="primary" onClick={handleOpenSetupWizard}>
            Open Setup Wizard
          </Button>
        </Modal.Footer>
      </Modal>
    </>
  );
}
