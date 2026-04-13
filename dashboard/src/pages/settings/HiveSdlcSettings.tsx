import type { ReactElement } from 'react';
import { Col, Form, Row } from 'react-bootstrap';

import HelpTip from '../../components/common/HelpTip';
import type { HiveConfig, HiveSdlcConfig } from '../../api/settingsTypes';

export interface HiveSdlcSettingsProps {
  form: HiveConfig;
  isManaged: boolean;
  setForm: (next: HiveConfig) => void;
}

interface HiveSdlcToggleDefinition {
  key: keyof HiveSdlcConfig;
  controlId: string;
  label: string;
  help: string;
}

const HIVE_SDLC_TOGGLES: HiveSdlcToggleDefinition[] = [
  {
    key: 'currentContextEnabled',
    controlId: 'hive-sdlc-current-context',
    label: 'Current context',
    help: 'Expose the current Hive card, thread, command, run, and golem ids to the agent.',
  },
  {
    key: 'cardReadEnabled',
    controlId: 'hive-sdlc-card-read',
    label: 'Read cards',
    help: 'Allow the agent to read the active or explicitly requested Hive card.',
  },
  {
    key: 'cardSearchEnabled',
    controlId: 'hive-sdlc-card-search',
    label: 'Search cards',
    help: 'Allow the agent to search Hive cards by board, service, kind, and related-card filters.',
  },
  {
    key: 'threadMessageEnabled',
    controlId: 'hive-sdlc-thread-message',
    label: 'Post thread notes',
    help: 'Allow the agent to write operator-facing SDLC notes into Hive card threads.',
  },
  {
    key: 'reviewRequestEnabled',
    controlId: 'hive-sdlc-review-request',
    label: 'Request review',
    help: 'Allow the agent to request Hive review for the active card.',
  },
  {
    key: 'followupCardCreateEnabled',
    controlId: 'hive-sdlc-followup-card-create',
    label: 'Create follow-up cards',
    help: 'Allow the agent to create Hive follow-up, subtask, or review cards.',
  },
  {
    key: 'lifecycleSignalEnabled',
    controlId: 'hive-sdlc-lifecycle-signal',
    label: 'Lifecycle signals',
    help: 'Allow the agent to emit structured lifecycle signals such as blockers, review requested, and work completed.',
  },
];

export function HiveSdlcSettings({ form, isManaged, setForm }: HiveSdlcSettingsProps): ReactElement {
  const sdlc = form.sdlc;
  const isHiveDisabled = form.enabled !== true;

  const handleToggle = (key: keyof HiveSdlcConfig, enabled: boolean): void => {
    setForm({ ...form, sdlc: { ...sdlc, [key]: enabled } });
  };

  return (
    <section className="border-top pt-3 mb-3">
      <div className="small fw-semibold mb-2">
        SDLC agent functions <HelpTip text="These built-in Hive tools are available only in Hive sessions. Each function can be disabled independently." />
      </div>
      <Row className="g-2">
        {HIVE_SDLC_TOGGLES.map((toggle) => (
          <Col md={6} key={toggle.key}>
            <Form.Check
              type="switch"
              id={toggle.controlId}
              label={<>{toggle.label} <HelpTip text={toggle.help} /></>}
              checked={sdlc[toggle.key] === true}
              onChange={(event) => handleToggle(toggle.key, event.target.checked)}
              disabled={isManaged || isHiveDisabled}
            />
          </Col>
        ))}
      </Row>
    </section>
  );
}
