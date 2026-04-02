import type { Dispatch, ReactElement, SetStateAction } from 'react';
import { Col, Form, Row } from 'react-bootstrap';

import type { SelfEvolvingConfig } from '../../../api/settings';
import HelpTip from '../../../components/common/HelpTip';
import { getExplicitModelTierOptions } from '../../../lib/modelTiers';

const JUDGE_TIER_OPTIONS = getExplicitModelTierOptions();

function toNullableFloat(value: string): number | null {
  const parsed = Number.parseFloat(value);
  return Number.isNaN(parsed) ? null : parsed;
}

interface SelfEvolvingJudgeTierSettingsProps {
  form: SelfEvolvingConfig;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
}

export function SelfEvolvingJudgeTierSettings({
  form,
  setForm,
}: SelfEvolvingJudgeTierSettingsProps): ReactElement {
  return (
    <>
      <Row className="g-3 mb-4">
        <Col md={4}>
          <Form.Group controlId="self-evolving-primary-tier">
            <Form.Label className="small fw-medium">
              Primary judge tier <HelpTip text="Default tier used for outcome and process judging." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.judge.primaryTier ?? 'smart'}
              onChange={(event) => setForm((current) => ({
                ...current,
                judge: { ...current.judge, primaryTier: event.target.value },
              }))}
            >
              {JUDGE_TIER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-tiebreaker-tier">
            <Form.Label className="small fw-medium">
              Tiebreaker tier <HelpTip text="Escalation tier used when judges disagree or confidence drops below the configured threshold." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.judge.tiebreakerTier ?? 'deep'}
              onChange={(event) => setForm((current) => ({
                ...current,
                judge: { ...current.judge, tiebreakerTier: event.target.value },
              }))}
            >
              {JUDGE_TIER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
        <Col md={4}>
          <Form.Group controlId="self-evolving-evolution-tier">
            <Form.Label className="small fw-medium">
              Evolution tier <HelpTip text="Tier used when deriving or tuning candidates after a run completes." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={form.judge.evolutionTier ?? 'deep'}
              onChange={(event) => setForm((current) => ({
                ...current,
                judge: { ...current.judge, evolutionTier: event.target.value },
              }))}
            >
              {JUDGE_TIER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
      </Row>

      <Row className="g-3 mb-4">
        <Col md={6}>
          <Form.Group controlId="self-evolving-uncertainty-threshold">
            <Form.Label className="small fw-medium">
              Uncertainty threshold <HelpTip text="Above this threshold, SelfEvolving escalates to the tiebreaker judge." />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={0}
              max={1}
              step="0.01"
              value={form.judge.uncertaintyThreshold ?? 0.22}
              onChange={(event) => setForm((current) => ({
                ...current,
                judge: {
                  ...current.judge,
                  uncertaintyThreshold: toNullableFloat(event.target.value),
                },
              }))}
            />
          </Form.Group>
        </Col>
        <Col md={6} className="d-flex align-items-end">
          <Form.Check
            type="switch"
            label={<>Require evidence anchors <HelpTip text="Judge verdicts must cite run evidence before they can influence promotion decisions." /></>}
            checked={form.judge.requireEvidenceAnchors ?? true}
            onChange={(event) => setForm((current) => ({
              ...current,
              judge: {
                ...current.judge,
                requireEvidenceAnchors: event.target.checked,
              },
            }))}
          />
        </Col>
      </Row>
    </>
  );
}
