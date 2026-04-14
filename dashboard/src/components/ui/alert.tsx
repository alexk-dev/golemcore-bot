import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils';

const alertVariants = cva(
  'rounded-2xl border px-4 py-3 text-sm leading-6 backdrop-blur-sm',
  {
    variants: {
      variant: {
        default: 'border-border/80 bg-card/70 text-foreground',
        secondary: 'border-border/80 bg-muted/70 text-foreground',
        info: 'border-cyan-500/20 bg-cyan-500/10 text-cyan-900 dark:text-cyan-100',
        warning: 'border-amber-500/20 bg-amber-500/10 text-amber-900 dark:text-amber-100',
        success: 'border-green-500/20 bg-green-500/10 text-green-900 dark:text-green-100',
        danger: 'border-destructive/20 bg-destructive/10 text-red-900 dark:text-red-100',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  }
);

export interface AlertProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof alertVariants> {}

function Alert({ className, variant, ...props }: AlertProps): React.ReactElement {
  return <div role="alert" className={cn(alertVariants({ variant }), className)} {...props} />;
}

export { Alert };
