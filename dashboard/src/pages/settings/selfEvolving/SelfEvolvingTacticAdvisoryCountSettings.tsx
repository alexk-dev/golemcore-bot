import type { Dispatch, ReactElement, SetStateAction } from 'react';
import { Col, Form, Row } from 'react-bootstrap';

import type { SelfEvolvingConfig } from '../../../api/settingsTypes';
import HelpTip from '../../../components/common/HelpTip';

interface AdvisoryCountSettingsProps {
  advisoryCount: number | null;
  disabled?: boolean;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
}

export function AdvisoryCountSettings({
  advisoryCount,
  disabled = false,
  setForm,
}: AdvisoryCountSettingsProps): ReactElement {
  return (
    <>
      <div className="mb-3">
        <h6 className="mb-2">Multi-tactic advisory</h6>
        <p className="text-body-secondary small mb-0">
          Controls how many top-ranked tactics are included in the advisory message injected into each turn.
          Higher values cover more aspects but add context length.
        </p>
      </div>
      <Row className="g-3 mb-4">
        <Col md={6}>
          <Form.Group controlId="self-evolving-advisory-count">
            <Form.Label className="small fw-medium">
              Advisory count{' '}
              <HelpTip text="Number of top-ranked tactics to include in the transient advisory (1-5). Default: 1." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={advisoryCount ?? 1}
              disabled={disabled}
              onChange={(event) => setForm((current) => ({
                ...current,
                tactics: {
                  ...current.tactics,
                  search: {
                    ...current.tactics.search,
                    advisoryCount: Number(event.target.value),
                  },
                },
              }))}
            >
              {[1, 2, 3, 4, 5].map((count) => (
                <option key={count} value={count}>{count}{count === 1 ? ' tactic' : ' tactics'}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
      </Row>
    </>
  );
}
