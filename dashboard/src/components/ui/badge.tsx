import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils';

const badgeVariants = cva(
  'inline-flex items-center justify-center rounded-full border px-2.5 py-1 text-[0.68rem] font-bold uppercase tracking-[0.16em]',
  {
    variants: {
      variant: {
        default: 'border-primary/30 bg-primary/15 text-primary',
        secondary: 'border-border bg-muted text-foreground',
        success: 'border-green-500/20 bg-green-500/15 text-green-600 dark:text-green-300',
        warning: 'border-amber-500/20 bg-amber-500/15 text-amber-700 dark:text-amber-200',
        destructive: 'border-destructive/20 bg-destructive/15 text-destructive dark:text-red-200',
        info: 'border-cyan-500/20 bg-cyan-500/15 text-cyan-700 dark:text-cyan-200',
        light: 'border-white/40 bg-white/75 text-slate-900',
        dark: 'border-slate-900/20 bg-slate-900 text-white dark:border-slate-100/20 dark:bg-slate-100 dark:text-slate-950',
      },
    },
    defaultVariants: {
      variant: 'secondary',
    },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps): React.ReactElement {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge };
