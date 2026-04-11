import type { ReactElement } from 'react';
import { Button, Form } from 'react-bootstrap';
import type { MemoryPreset } from '../../api/settingsTypes';
import HelpTip from '../../components/common/HelpTip';

export interface MemoryPresetPickerProps {
  presets: MemoryPreset[];
  presetsLoading: boolean;
  selectedPreset: MemoryPreset | null;
  onSelectPreset: (id: string) => void;
  onApplyPreset: () => void;
}

export function MemoryPresetPicker({
  presets,
  presetsLoading,
  selectedPreset,
  onSelectPreset,
  onApplyPreset,
}: MemoryPresetPickerProps): ReactElement {
  const hasPresets = presets.length > 0;
  const canApplyPreset = !presetsLoading && selectedPreset != null;

  return (
    <Form.Group className="mb-3">
      <Form.Label className="small fw-medium">
        Memory Preset <HelpTip text="Preset profiles tuned for different workloads. Select one, then click Apply Preset to copy values into the local form." />
      </Form.Label>
      {presetsLoading && <div className="small text-body-secondary">Loading memory presets...</div>}
      {!presetsLoading && !hasPresets && <div className="small text-body-secondary">No memory presets available.</div>}
      {!presetsLoading && hasPresets && (
        <>
          <div className="memory-preset-grid" role="radiogroup" aria-label="Memory preset choices">
            {presets.map((preset) => {
              const isSelected = selectedPreset?.id === preset.id;
              return (
                <button
                  key={preset.id}
                  type="button"
                  role="radio"
                  aria-checked={isSelected}
                  className={`memory-preset-option${isSelected ? ' is-selected' : ''}`}
                  onClick={() => onSelectPreset(preset.id)}
                >
                  <span className="memory-preset-option-title-row">
                    <span className="memory-preset-option-title">{preset.label}</span>
                    <span className="memory-preset-option-id">{preset.id}</span>
                  </span>
                  <span className="memory-preset-option-comment">{preset.comment}</span>
                </button>
              );
            })}
          </div>
          <div className="memory-preset-actions">
            <div className="small text-body-secondary">
              Selected: <strong>{selectedPreset?.label ?? 'None'}</strong>
            </div>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={onApplyPreset}
              disabled={!canApplyPreset}
            >
              Apply Preset
            </Button>
          </div>
        </>
      )}
      <div className="small text-body-secondary mt-2">
        Tip: preset values are staged locally. Use <strong>Save</strong> to persist.
      </div>
    </Form.Group>
  );
}
