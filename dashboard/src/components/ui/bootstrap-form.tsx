import * as React from 'react';
import { Input, Select, Textarea } from './field';
import { cn } from '../../lib/utils';

interface FormContextValue {
  controlId?: string;
}

const FormContext = React.createContext<FormContextValue>({});

type FormComponent = React.ForwardRefExoticComponent<
  React.FormHTMLAttributes<HTMLFormElement> & React.RefAttributes<HTMLFormElement>
> & {
  Group: typeof FormGroup;
  Label: typeof FormLabel;
  Control: typeof FormControl;
  Select: typeof FormSelect;
  Check: typeof FormCheck;
  Range: typeof FormRange;
  Text: typeof FormText;
};

interface FormGroupProps extends React.HTMLAttributes<HTMLDivElement> {
  controlId?: string;
}

function FormGroup({ className, controlId, ...props }: FormGroupProps): React.ReactElement {
  return (
    <FormContext.Provider value={{ controlId }}>
      <div className={cn(className)} {...props} />
    </FormContext.Provider>
  );
}

function FormLabel({ className, htmlFor, ...props }: React.LabelHTMLAttributes<HTMLLabelElement>): React.ReactElement {
  const context = React.useContext(FormContext);
  return <label className={cn('form-label', className)} htmlFor={htmlFor ?? context.controlId} {...props} />;
}

interface BaseFormControlProps {
  size?: 'sm' | 'lg';
  isInvalid?: boolean;
}

type TextInputControlProps = BaseFormControlProps
  & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'>
  & { as?: 'input' };

type TextareaControlProps = BaseFormControlProps
  & React.TextareaHTMLAttributes<HTMLTextAreaElement>
  & { as: 'textarea' };

type FormControlProps = TextInputControlProps | TextareaControlProps;

const FormControl = React.forwardRef<HTMLInputElement | HTMLTextAreaElement, FormControlProps>(
  ({ as = 'input', className, id, size, isInvalid = false, ...props }, ref) => {
    const context = React.useContext(FormContext);
    const sizeClassName = size === 'sm' ? 'min-h-9 text-xs' : size === 'lg' ? 'min-h-11 text-base' : undefined;
    if (as === 'textarea') {
      const textareaProps = props as React.TextareaHTMLAttributes<HTMLTextAreaElement>;
      return (
        <Textarea
          ref={ref as React.ForwardedRef<HTMLTextAreaElement>}
          id={id ?? context.controlId}
          className={cn('form-control', sizeClassName, isInvalid && 'border-destructive', className)}
          {...textareaProps}
        />
      );
    }

    const inputProps = props as React.InputHTMLAttributes<HTMLInputElement>;
    return (
      <Input
        ref={ref as React.ForwardedRef<HTMLInputElement>}
        id={id ?? context.controlId}
        className={cn('form-control', sizeClassName, isInvalid && 'border-destructive', className)}
        {...inputProps}
      />
    );
  }
);

FormControl.displayName = 'FormControl';

interface FormSelectProps extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  size?: 'sm' | 'lg';
}

const FormSelect = React.forwardRef<HTMLSelectElement, FormSelectProps>(({ className, id, size, ...props }, ref) => {
  const context = React.useContext(FormContext);
  const sizeClassName = size === 'sm' ? 'min-h-9 text-xs' : size === 'lg' ? 'min-h-11 text-base' : undefined;
  return (
    <Select
      ref={ref}
      id={id ?? context.controlId}
      className={cn('form-select', sizeClassName, className)}
      {...props}
    />
  );
});

FormSelect.displayName = 'FormSelect';

interface FormCheckProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label?: React.ReactNode;
  type?: 'checkbox' | 'switch' | 'radio';
}

function FormCheck({
  className,
  id,
  label,
  type = 'checkbox',
  checked,
  disabled,
  onChange,
  ...props
}: FormCheckProps): React.ReactElement {
  const generatedId = React.useId();
  const context = React.useContext(FormContext);
  const controlId = id ?? context.controlId ?? generatedId;

  if (type === 'switch') {
    return (
      <label className={cn('form-check items-center justify-between rounded-2xl border border-border/70 bg-card/60 px-3 py-3', className)} htmlFor={controlId}>
        <span className="flex-1 text-sm leading-6 text-foreground">{label}</span>
        <span className="relative inline-flex h-6 w-11 items-center">
          <input
            id={controlId}
            className="peer sr-only"
            type="checkbox"
            checked={checked}
            disabled={disabled}
            onChange={onChange}
            {...props}
          />
          <span className="h-6 w-11 rounded-full bg-muted transition-colors duration-200 peer-checked:bg-primary peer-disabled:opacity-50" />
          <span className="absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform duration-200 peer-checked:translate-x-5 peer-disabled:opacity-50" />
        </span>
      </label>
    );
  }

  return (
    <div className={cn('form-check', className)}>
      <input
        id={controlId}
        className="form-check-input"
        type={type}
        checked={checked}
        disabled={disabled}
        onChange={onChange}
        {...props}
      />
      {label != null && <label className="form-check-label" htmlFor={controlId}>{label}</label>}
    </div>
  );
}

const FormRange = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(({ className, id, ...props }, ref) => {
  const context = React.useContext(FormContext);
  return <input ref={ref} id={id ?? context.controlId} type="range" className={cn('form-range', className)} {...props} />;
});

FormRange.displayName = 'FormRange';

function FormText({ className, ...props }: React.HTMLAttributes<HTMLElement>): React.ReactElement {
  return <small className={cn('form-text', className)} {...props} />;
}

const BaseForm = React.forwardRef<HTMLFormElement, React.FormHTMLAttributes<HTMLFormElement>>(
  ({ className, ...props }, ref) => <form ref={ref} className={cn(className)} {...props} />
);

BaseForm.displayName = 'Form';

const Form = BaseForm as FormComponent;
Form.Group = FormGroup;
Form.Label = FormLabel;
Form.Control = FormControl;
Form.Select = FormSelect;
Form.Check = FormCheck;
Form.Range = FormRange;
Form.Text = FormText;

interface InputGroupComponent extends React.ForwardRefExoticComponent<
  InputGroupProps & React.RefAttributes<HTMLDivElement>
> {
  Text: typeof InputGroupText;
}

interface InputGroupProps extends React.HTMLAttributes<HTMLDivElement> {
  size?: 'sm' | 'lg';
}

function InputGroupText({ className, ...props }: React.HTMLAttributes<HTMLSpanElement>): React.ReactElement {
  return <span className={cn('input-group-text', className)} {...props} />;
}

const BaseInputGroup = React.forwardRef<HTMLDivElement, InputGroupProps>(
  ({ className, size, ...props }, ref) => (
    <div
      ref={ref}
      className={cn('input-group', size === 'sm' && 'min-h-9 text-xs', size === 'lg' && 'min-h-11 text-base', className)}
      {...props}
    />
  )
);

BaseInputGroup.displayName = 'InputGroup';

const InputGroup = BaseInputGroup as InputGroupComponent;
InputGroup.Text = InputGroupText;

export { Form, InputGroup };
