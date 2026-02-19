import type { ReactElement, ReactNode } from 'react';
import HelpTip from './HelpTip';

interface SettingsCardTitleProps {
  title: ReactNode;
  tip?: string;
  className?: string;
}

function buildClassName(extraClass?: string): string {
  const baseClass = 'card-title h6 settings-card-title';
  const hasMarginClass = extraClass != null && /\bmb-\d\b/.test(extraClass);
  const marginClass = hasMarginClass ? '' : ' mb-3';
  return extraClass != null && extraClass.length > 0
    ? `${baseClass}${marginClass} ${extraClass}`
    : `${baseClass}${marginClass}`;
}

export default function SettingsCardTitle({ title, tip, className }: SettingsCardTitleProps): ReactElement {
  return (
    <h2 className={buildClassName(className)}>
      <span>{title}</span>
      {tip != null && tip.length > 0 && <HelpTip text={tip} />}
    </h2>
  );
}
