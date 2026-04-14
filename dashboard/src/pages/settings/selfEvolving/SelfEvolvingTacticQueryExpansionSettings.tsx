import type { Dispatch, ReactElement, SetStateAction } from 'react';
import { Col, Form, Row } from '../../../components/ui/tailwind-components';

import type { SelfEvolvingConfig, SelfEvolvingTacticQueryExpansionConfig } from '../../../api/settingsTypes';
import HelpTip from '../../../components/common/HelpTip';
import { getExplicitModelTierOptions } from '../../../lib/modelTiers';

const QUERY_EXPANSION_TIER_OPTIONS = getExplicitModelTierOptions();

interface QueryExpansionSettingsProps {
  queryExpansion: SelfEvolvingTacticQueryExpansionConfig;
  disabled?: boolean;
  setForm: Dispatch<SetStateAction<SelfEvolvingConfig>>;
}

export function QueryExpansionSettings({
  queryExpansion,
  disabled = false,
  setForm,
}: QueryExpansionSettingsProps): ReactElement {
  const updateQueryExpansion = (
    updater: (current: SelfEvolvingTacticQueryExpansionConfig) => SelfEvolvingTacticQueryExpansionConfig,
  ): void => {
    setForm((current) => ({
      ...current,
      tactics: {
        ...current.tactics,
        search: {
          ...current.tactics.search,
          queryExpansion: updater(current.tactics.search.queryExpansion),
        },
      },
    }));
  };

  return (
    <>
      <div className="mb-3">
        <h6 className="mb-2">LLM query expansion</h6>
        <p className="text-body-secondary small mb-0">
          When enabled, the user message is rewritten by an LLM into 2-3 search queries covering problem domain,
          tooling, and failure recovery. Results are cached per turn and merged with keyword-based views.
        </p>
      </div>
      <Form.Check
        type="switch"
        label={<>Enable LLM query expansion <HelpTip text="Rewrites the user message into richer search queries via LLM before tactic retrieval. Disable to use keyword-only expansion." /></>}
        checked={queryExpansion.enabled ?? true}
        disabled={disabled}
        onChange={(event) => updateQueryExpansion((current) => ({ ...current, enabled: event.target.checked }))}
        className="mb-3"
      />
      <Row className="g-3 mb-4">
        <Col md={6}>
          <Form.Group controlId="self-evolving-query-expansion-tier">
            <Form.Label className="small fw-medium">
              Expansion tier <HelpTip text="Model tier used for query rewriting. Lower tiers are faster and cheaper; higher tiers produce more nuanced expansions." />
            </Form.Label>
            <Form.Select
              size="sm"
              value={queryExpansion.tier ?? 'balanced'}
              disabled={disabled || !(queryExpansion.enabled ?? true)}
              onChange={(event) => updateQueryExpansion((current) => ({ ...current, tier: event.target.value }))}
            >
              {QUERY_EXPANSION_TIER_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </Form.Select>
          </Form.Group>
        </Col>
      </Row>
    </>
  );
}
