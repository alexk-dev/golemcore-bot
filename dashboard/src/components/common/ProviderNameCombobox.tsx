import { type ReactElement } from 'react';
import { AutocompleteCombobox, type AutocompleteComboboxProps } from './AutocompleteCombobox';

export interface ProviderNameComboboxProps extends Omit<AutocompleteComboboxProps, 'emptyState'> {}

export function ProviderNameCombobox(props: ProviderNameComboboxProps): ReactElement {
  return <AutocompleteCombobox {...props} />;
}
