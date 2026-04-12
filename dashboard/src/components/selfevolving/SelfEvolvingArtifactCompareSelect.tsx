import type { ReactElement } from 'react';
import { Form } from 'react-bootstrap';

import type { SelfEvolvingArtifactCompareOption } from '../../api/selfEvolving';

interface SelfEvolvingArtifactCompareSelectProps {
  fromId: string | null;
  toId: string | null;
  options: SelfEvolvingArtifactCompareOption[];
  onSelectPair: (fromId: string, toId: string) => void;
}

export function SelfEvolvingArtifactCompareSelect({
  fromId,
  toId,
  options,
  onSelectPair,
}: SelfEvolvingArtifactCompareSelectProps): ReactElement {
  return (
    <div className="d-flex flex-column gap-2">
      <Form.Select
        value={`${fromId ?? ''}::${toId ?? ''}`}
        onChange={(event) => {
          const [nextFromId, nextToId] = event.target.value.split('::');
          onSelectPair(nextFromId, nextToId);
        }}
      >
        {options.map((option) => (
          <option key={`${option.fromId}-${option.toId}`} value={`${option.fromId}::${option.toId}`}>
            {option.label.split('_').join(' ')}
          </option>
        ))}
      </Form.Select>
      <div className="small text-body-secondary">
        {fromId ?? 'n/a'} ? {toId ?? 'n/a'}
      </div>
    </div>
  );
}
