import * as React from 'react';
import { cn } from '../../lib/utils';

export type SkeletonProps = React.HTMLAttributes<HTMLDivElement>;

function Skeleton({ className, ...props }: SkeletonProps): React.ReactElement {
  return <div className={cn('animate-pulse rounded-xl bg-muted/80', className)} {...props} />;
}

export { Skeleton };
