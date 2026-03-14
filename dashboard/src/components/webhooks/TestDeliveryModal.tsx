import { type ReactElement, useState } from 'react';
import { Button, Col, Form, InputGroup, Modal, Row } from 'react-bootstrap';
import type { TestWebhookDeliveryRequest } from '../../api/webhookDeliveries';

interface TestDeliveryModalProps {
  show: boolean;
  saving: boolean;
  onHide: () => void;
  onSubmit: (request: TestWebhookDeliveryRequest) => Promise<void>;
}

interface TestDeliveryFormState {
  callbackUrl: string;
  runId: string;
  chatId: string;
  model: string;
  payloadStatus: 'completed' | 'failed';
  response: string;
  durationMs: string;
  errorMessage: string;
}

const INITIAL_FORM_STATE: TestDeliveryFormState = {
  callbackUrl: '',
  runId: '',
  chatId: '',
  model: '',
  payloadStatus: 'completed',
  response: 'Webhook test callback payload',
  durationMs: '500',
  errorMessage: '',
};

export function TestDeliveryModal({ show, saving, onHide, onSubmit }: TestDeliveryModalProps): ReactElement {
  const [formState, setFormState] = useState<TestDeliveryFormState>(INITIAL_FORM_STATE);

  const updateFormState = <T extends keyof TestDeliveryFormState>(field: T, value: TestDeliveryFormState[T]): void => {
    setFormState((current) => ({ ...current, [field]: value }));
  };

  const submitDisabled = saving || formState.callbackUrl.trim().length === 0;

  const handleSubmit = async (): Promise<void> => {
    const parsedDuration = Number.parseInt(formState.durationMs, 10);
    const request: TestWebhookDeliveryRequest = {
      callbackUrl: formState.callbackUrl.trim(),
      runId: formState.runId.trim().length > 0 ? formState.runId.trim() : null,
      chatId: formState.chatId.trim().length > 0 ? formState.chatId.trim() : null,
      model: formState.model.trim().length > 0 ? formState.model.trim() : null,
      payloadStatus: formState.payloadStatus,
      response: formState.response.trim().length > 0 ? formState.response : null,
      durationMs: Number.isFinite(parsedDuration) && parsedDuration > 0 ? parsedDuration : 1,
      errorMessage: formState.payloadStatus === 'failed' && formState.errorMessage.trim().length > 0
        ? formState.errorMessage.trim()
        : null,
    };

    await onSubmit(request);
    setFormState(INITIAL_FORM_STATE);
    onHide();
  };

  return (
    <Modal show={show} onHide={onHide} centered>
      <Modal.Header closeButton>
        <Modal.Title>Send test callback</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        <div className="d-flex flex-column gap-3">
          <Form.Group>
            <Form.Label className="small fw-medium">Callback URL</Form.Label>
            <Form.Control
              size="sm"
              value={formState.callbackUrl}
              onChange={(event) => updateFormState('callbackUrl', event.target.value)}
              placeholder="https://example.com/webhook-result"
            />
          </Form.Group>

          <Row className="g-2">
            <Col md={6}>
              <Form.Group>
                <Form.Label className="small fw-medium">Run ID</Form.Label>
                <Form.Control
                  size="sm"
                  value={formState.runId}
                  onChange={(event) => updateFormState('runId', event.target.value)}
                />
              </Form.Group>
            </Col>
            <Col md={6}>
              <Form.Group>
                <Form.Label className="small fw-medium">Chat ID</Form.Label>
                <Form.Control
                  size="sm"
                  value={formState.chatId}
                  onChange={(event) => updateFormState('chatId', event.target.value)}
                />
              </Form.Group>
            </Col>
          </Row>

          <Row className="g-2">
            <Col md={6}>
              <Form.Group>
                <Form.Label className="small fw-medium">Model</Form.Label>
                <Form.Control
                  size="sm"
                  value={formState.model}
                  onChange={(event) => updateFormState('model', event.target.value)}
                />
              </Form.Group>
            </Col>
            <Col md={6}>
              <Form.Group>
                <Form.Label className="small fw-medium">Duration (ms)</Form.Label>
                <InputGroup size="sm">
                  <Form.Control
                    type="number"
                    value={formState.durationMs}
                    onChange={(event) => updateFormState('durationMs', event.target.value)}
                    min={1}
                  />
                  <InputGroup.Text>ms</InputGroup.Text>
                </InputGroup>
              </Form.Group>
            </Col>
          </Row>

          <Form.Group>
            <Form.Label className="small fw-medium">Payload status</Form.Label>
            <Form.Select
              size="sm"
              value={formState.payloadStatus}
              onChange={(event) => updateFormState('payloadStatus', event.target.value === 'failed' ? 'failed' : 'completed')}
            >
              <option value="completed">completed</option>
              <option value="failed">failed</option>
            </Form.Select>
          </Form.Group>

          <Form.Group>
            <Form.Label className="small fw-medium">Response body</Form.Label>
            <Form.Control
              as="textarea"
              rows={3}
              value={formState.response}
              onChange={(event) => updateFormState('response', event.target.value)}
            />
          </Form.Group>

          {formState.payloadStatus === 'failed' && (
            <Form.Group>
              <Form.Label className="small fw-medium">Error message</Form.Label>
              <Form.Control
                size="sm"
                value={formState.errorMessage}
                onChange={(event) => updateFormState('errorMessage', event.target.value)}
              />
            </Form.Group>
          )}
        </div>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" size="sm" variant="secondary" onClick={onHide} disabled={saving}>
          Cancel
        </Button>
        <Button type="button" size="sm" variant="primary" onClick={() => { void handleSubmit(); }} disabled={submitDisabled}>
          {saving ? 'Sending...' : 'Send'}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
