import type { ReactElement } from 'react';
import HelpTip from '../common/HelpTip';
import { fieldLabelClassName } from './HookMappingFormUtils';

interface HookMappingFieldHeadingProps {
  label: string;
  help: string;
}

export function HookMappingFieldHeading({ label, help }: HookMappingFieldHeadingProps): ReactElement {
  return (
    <div className={fieldLabelClassName}>
      <span>{label}</span>
      <HelpTip text={help} />
    </div>
  );
}
