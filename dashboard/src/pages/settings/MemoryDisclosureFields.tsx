import type { ReactElement } from 'react';
import { Form } from 'react-bootstrap';
import type {
  MemoryDiagnosticsVerbosity,
  MemoryDisclosureMode,
  MemoryPromptStyle,
} from '../../api/settings';
import HelpTip from '../../components/common/HelpTip';

export interface MemoryDisclosureFieldsProps {
  disclosureMode: MemoryDisclosureMode;
  promptStyle: MemoryPromptStyle;
  toolExpansionEnabled: boolean;
  disclosureHintsEnabled: boolean;
  detailMinScore: number;
  diagnosticsVerbosity: MemoryDiagnosticsVerbosity;
  onDisclosureModeChange: (value: MemoryDisclosureMode) => void;
  onPromptStyleChange: (value: MemoryPromptStyle) => void;
  onToolExpansionChange: (value: boolean) => void;
  onDisclosureHintsChange: (value: boolean) => void;
  onDetailMinScoreChange: (value: string) => void;
  onDiagnosticsVerbosityChange: (value: MemoryDiagnosticsVerbosity) => void;
}

function toDisclosureMode(value: string): MemoryDisclosureMode {
  switch (value) {
    case 'index':
    case 'summary':
    case 'selective_detail':
    case 'full_pack':
      return value;
    default:
      return 'summary';
  }
}

function toPromptStyle(value: string): MemoryPromptStyle {
  switch (value) {
    case 'compact':
    case 'balanced':
    case 'rich':
      return value;
    default:
      return 'balanced';
  }
}

function toDiagnosticsVerbosity(value: string): MemoryDiagnosticsVerbosity {
  switch (value) {
    case 'off':
    case 'basic':
    case 'detailed':
      return value;
    default:
      return 'basic';
  }
}

export function MemoryDisclosureFields({
  disclosureMode,
  promptStyle,
  toolExpansionEnabled,
  disclosureHintsEnabled,
  detailMinScore,
  diagnosticsVerbosity,
  onDisclosureModeChange,
  onPromptStyleChange,
  onToolExpansionChange,
  onDisclosureHintsChange,
  onDetailMinScoreChange,
  onDiagnosticsVerbosityChange,
}: MemoryDisclosureFieldsProps): ReactElement {
  return (
    <>
      <div className="small fw-semibold text-uppercase text-body-secondary mt-4 mb-3">
        Progressive Disclosure
      </div>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Disclosure mode <HelpTip text="Choose how much memory detail is injected into the prompt before the agent needs on-demand expansion." />
        </Form.Label>
        <Form.Select
          size="sm"
          value={disclosureMode}
          onChange={(e) => onDisclosureModeChange(toDisclosureMode(e.target.value))}
        >
          <option value="index">Index</option>
          <option value="summary">Summary</option>
          <option value="selective_detail">Selective detail</option>
          <option value="full_pack">Full pack</option>
        </Form.Select>
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Prompt style <HelpTip text="Controls how dense each rendered memory section should be." />
        </Form.Label>
        <Form.Select
          size="sm"
          value={promptStyle}
          onChange={(e) => onPromptStyleChange(toPromptStyle(e.target.value))}
        >
          <option value="compact">Compact</option>
          <option value="balanced">Balanced</option>
          <option value="rich">Rich</option>
        </Form.Select>
      </Form.Group>
      <Form.Check
        type="switch"
        label={<>Tool expansion <HelpTip text="Allow prompts to hint that memory_read and memory_expand_section can reveal more detail on demand." /></>}
        checked={toolExpansionEnabled}
        onChange={(e) => onToolExpansionChange(e.target.checked)}
        className="mb-3"
      />
      <Form.Check
        type="switch"
        label={<>Disclosure hints <HelpTip text="Expose follow-up hints in prompt memory sections when additional detail is available." /></>}
        checked={disclosureHintsEnabled}
        onChange={(e) => onDisclosureHintsChange(e.target.checked)}
        className="mb-3"
      />
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Detail minimum score <HelpTip text="Only high-confidence items above this score can appear as raw detail snippets." />
        </Form.Label>
        <Form.Control
          size="sm"
          type="number"
          min={0}
          max={1}
          step={0.01}
          value={detailMinScore}
          onChange={(e) => onDetailMinScoreChange(e.target.value)}
        />
      </Form.Group>
      <Form.Group className="mb-3">
        <Form.Label className="small fw-medium">
          Diagnostics verbosity <HelpTip text="Controls how much structured memory telemetry is exposed in diagnostics." />
        </Form.Label>
        <Form.Select
          size="sm"
          value={diagnosticsVerbosity}
          onChange={(e) => onDiagnosticsVerbosityChange(toDiagnosticsVerbosity(e.target.value))}
        >
          <option value="off">Off</option>
          <option value="basic">Basic</option>
          <option value="detailed">Detailed</option>
        </Form.Select>
      </Form.Group>
    </>
  );
}
