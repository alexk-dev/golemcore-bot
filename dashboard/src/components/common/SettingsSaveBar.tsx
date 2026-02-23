import type { ReactElement, ReactNode } from 'react';

type SaveBarVariant = 'default' | 'tools';

interface SettingsSaveBarProps {
  children: ReactNode;
  className?: string;
  variant?: SaveBarVariant;
}

interface SaveStateHintProps {
  isDirty: boolean;
}

function buildClassName(baseClass: string, extraClass?: string): string {
  const sharedClass = `${baseClass} d-flex align-items-center gap-2 flex-wrap`;
  return extraClass != null && extraClass.length > 0 ? `${sharedClass} ${extraClass}` : sharedClass;
}

export function SettingsSaveBar({ children, className, variant = 'default' }: SettingsSaveBarProps): ReactElement {
  const baseClass = variant === 'tools' ? 'tools-savebar' : 'settings-savebar';

  return (
    <div className={buildClassName(baseClass, className)}>
      {children}
    </div>
  );
}

export function SaveStateHint({ isDirty }: SaveStateHintProps): ReactElement {
  return (
    <small className="text-body-secondary" role="status" aria-live="polite" aria-atomic="true">
      {isDirty ? 'Unsaved changes' : 'All changes saved'}
    </small>
  );
}
