import type { ReactElement } from 'react';
import { AutocompleteCombobox, type AutocompleteComboboxProps } from './AutocompleteCombobox';

export type ProviderNameComboboxProps = Omit<AutocompleteComboboxProps, 'emptyState'>;

export function ProviderNameCombobox(props: ProviderNameComboboxProps): ReactElement {
  return <AutocompleteCombobox {...props} />;
}
