import * as React from 'react';
import { Badge } from './badge';
import { cn } from '../../lib/utils';
import { Skeleton } from './skeleton';

type ResponsiveValue = number | 'auto' | undefined;

const COL_SPAN_MAP: Record<number, string> = {
  1: 'col-span-1',
  2: 'col-span-2',
  3: 'col-span-3',
  4: 'col-span-4',
  5: 'col-span-5',
  6: 'col-span-6',
  7: 'col-span-7',
  8: 'col-span-8',
  9: 'col-span-9',
  10: 'col-span-10',
  11: 'col-span-11',
  12: 'col-span-12',
};

const RESPONSIVE_COL_SPAN_MAP: Record<'sm' | 'md' | 'lg' | 'xl', Record<number, string>> = {
  sm: {
    1: 'sm:col-span-1',
    2: 'sm:col-span-2',
    3: 'sm:col-span-3',
    4: 'sm:col-span-4',
    5: 'sm:col-span-5',
    6: 'sm:col-span-6',
    7: 'sm:col-span-7',
    8: 'sm:col-span-8',
    9: 'sm:col-span-9',
    10: 'sm:col-span-10',
    11: 'sm:col-span-11',
    12: 'sm:col-span-12',
  },
  md: {
    1: 'md:col-span-1',
    2: 'md:col-span-2',
    3: 'md:col-span-3',
    4: 'md:col-span-4',
    5: 'md:col-span-5',
    6: 'md:col-span-6',
    7: 'md:col-span-7',
    8: 'md:col-span-8',
    9: 'md:col-span-9',
    10: 'md:col-span-10',
    11: 'md:col-span-11',
    12: 'md:col-span-12',
  },
  lg: {
    1: 'lg:col-span-1',
    2: 'lg:col-span-2',
    3: 'lg:col-span-3',
    4: 'lg:col-span-4',
    5: 'lg:col-span-5',
    6: 'lg:col-span-6',
    7: 'lg:col-span-7',
    8: 'lg:col-span-8',
    9: 'lg:col-span-9',
    10: 'lg:col-span-10',
    11: 'lg:col-span-11',
    12: 'lg:col-span-12',
  },
  xl: {
    1: 'xl:col-span-1',
    2: 'xl:col-span-2',
    3: 'xl:col-span-3',
    4: 'xl:col-span-4',
    5: 'xl:col-span-5',
    6: 'xl:col-span-6',
    7: 'xl:col-span-7',
    8: 'xl:col-span-8',
    9: 'xl:col-span-9',
    10: 'xl:col-span-10',
    11: 'xl:col-span-11',
    12: 'xl:col-span-12',
  },
};

const RESPONSIVE_AUTO_MAP: Record<'sm' | 'md' | 'lg' | 'xl', string> = {
  sm: 'sm:col-auto',
  md: 'md:col-auto',
  lg: 'lg:col-auto',
  xl: 'xl:col-auto',
};

function spanClass(prefix: 'sm' | 'md' | 'lg' | 'xl', value: ResponsiveValue): string | null {
  if (value == null) {
    return null;
  }
  if (value === 'auto') {
    return RESPONSIVE_AUTO_MAP[prefix];
  }
  return RESPONSIVE_COL_SPAN_MAP[prefix][value];
}

function valueToSpan(value: ResponsiveValue): string {
  if (value == null) {
    return 'col-span-12';
  }
  if (value === 'auto') {
    return 'col-auto';
  }
  return COL_SPAN_MAP[value];
}

export interface ContainerProps extends React.HTMLAttributes<HTMLDivElement> {
  fluid?: boolean;
}

function Container({ className, fluid = false, ...props }: ContainerProps): React.ReactElement {
  return (
    <div
      className={cn(fluid ? 'w-full px-4 sm:px-6 lg:px-8' : 'mx-auto w-full max-w-7xl px-4 sm:px-6 lg:px-8', className)}
      {...props}
    />
  );
}

interface ButtonGroupProps extends React.HTMLAttributes<HTMLDivElement> {
  size?: 'sm' | 'lg';
}

function ButtonGroup({ className, size, ...props }: ButtonGroupProps): React.ReactElement {
  return (
    <div
      className={cn('btn-group', size === 'sm' && '[&_.btn]:h-8 [&_.btn]:px-3 [&_.btn]:text-xs', size === 'lg' && '[&_.btn]:h-11 [&_.btn]:px-6 [&_.btn]:text-base', className)}
      role="group"
      {...props}
    />
  );
}

export type RowProps = React.HTMLAttributes<HTMLDivElement>;

function Row({ className, ...props }: RowProps): React.ReactElement {
  return <div className={cn('grid grid-cols-12 gap-4', className)} {...props} />;
}

export interface ColProps extends React.HTMLAttributes<HTMLDivElement> {
  xs?: ResponsiveValue;
  sm?: ResponsiveValue;
  md?: ResponsiveValue;
  lg?: ResponsiveValue;
  xl?: ResponsiveValue;
}

function Col({ className, xs, sm, md, lg, xl, ...props }: ColProps): React.ReactElement {
  const classes = [
    valueToSpan(xs),
    spanClass('sm', sm),
    spanClass('md', md),
    spanClass('lg', lg),
    spanClass('xl', xl),
  ];
  return <div className={cn(classes, className)} {...props} />;
}

export interface TableProps extends React.TableHTMLAttributes<HTMLTableElement> {
  responsive?: boolean;
  hover?: boolean;
  size?: 'sm' | 'lg';
}

function Table({ className, responsive, hover, size, ...props }: TableProps): React.ReactElement {
  const tableElement = (
    <table
      className={cn(
        'table',
        size === 'sm' && 'text-xs',
        size === 'lg' && 'text-base',
        hover && '[&_tbody_tr:hover]:bg-primary/5 [&_tbody_tr:hover]:transition-colors',
        className
      )}
      {...props}
    />
  );

  if (!responsive) {
    return tableElement;
  }

  return <div className="w-full overflow-x-auto">{tableElement}</div>;
}

interface ListGroupItemProps extends React.HTMLAttributes<HTMLElement> {
  action?: boolean;
  active?: boolean;
}

function ListGroupItem({ className, action = false, active = false, children, onClick, ...props }: ListGroupItemProps): React.ReactElement {
  const resolvedClassName = cn(
    'list-group-item text-left',
    action && 'hover:bg-primary/5',
    active && 'bg-primary/10 text-foreground',
    className
  );

  if (action || onClick != null) {
    return (
      <button
        type="button"
        className={resolvedClassName}
        onClick={onClick}
        {...(props as React.ButtonHTMLAttributes<HTMLButtonElement>)}
      >
        {children}
      </button>
    );
  }

  return (
    <div className={resolvedClassName} {...props}>
      {children}
    </div>
  );
}

interface ListGroupComponent extends React.ForwardRefExoticComponent<
  ListGroupProps & React.RefAttributes<HTMLDivElement>
> {
  Item: typeof ListGroupItem;
}

interface ListGroupProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'flush';
}

const BaseListGroup = React.forwardRef<HTMLDivElement, ListGroupProps>(
  ({ className, variant, ...props }, ref) => (
    <div
      ref={ref}
      className={cn('list-group', variant === 'flush' && 'rounded-none border-x-0 border-t-0', className)}
      {...props}
    />
  )
);

BaseListGroup.displayName = 'ListGroup';

const ListGroup = BaseListGroup as ListGroupComponent;
ListGroup.Item = ListGroupItem;

type NavLinkClassName = string | ((props: { isActive: boolean }) => string);

interface NavLinkProps {
  as?: React.ElementType;
  className?: NavLinkClassName;
  children?: React.ReactNode;
  [key: string]: unknown;
}

function resolveNavLinkClassName(baseClassName: string, className: NavLinkClassName | undefined): NavLinkClassName {
  if (typeof className === 'function') {
    return ({ isActive }: { isActive: boolean }) => cn(baseClassName, isActive && 'active', className({ isActive }));
  }

  return ({ isActive }: { isActive: boolean }) => cn(baseClassName, isActive && 'active', className);
}

function NavLinkComponent({ as, className, ...props }: NavLinkProps): React.ReactElement {
  const Component = as ?? 'a';
  const resolvedClassName = resolveNavLinkClassName('nav-link', className);
  return <Component className={resolvedClassName} {...props} />;
}

interface NavComponent extends React.ForwardRefExoticComponent<
  React.HTMLAttributes<HTMLDivElement> & React.RefAttributes<HTMLDivElement>
> {
  Link: typeof NavLinkComponent;
}

const BaseNav = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => <div ref={ref} className={cn('nav', className)} {...props} />
);

BaseNav.displayName = 'Nav';

const Nav = BaseNav as NavComponent;
Nav.Link = NavLinkComponent;

export interface SpinnerProps extends React.HTMLAttributes<HTMLSpanElement> {
  animation?: 'border';
  size?: 'sm';
}

function Spinner({ className, size, ...props }: SpinnerProps): React.ReactElement {
  return <span className={cn('spinner-border', size === 'sm' && 'spinner-border-sm', className)} aria-hidden="true" {...props} />;
}

export interface PlaceholderProps extends React.HTMLAttributes<HTMLDivElement | HTMLSpanElement> {
  as?: 'div' | 'span';
  animation?: 'glow';
  xs?: number;
}

function Placeholder({ as = 'span', animation, className, xs, children, ...props }: PlaceholderProps): React.ReactElement {
  if (children != null) {
    if (as === 'div') {
      return (
        <div className={cn(animation === 'glow' && 'animate-pulse', className)} {...(props as React.HTMLAttributes<HTMLDivElement>)}>
          {children}
        </div>
      );
    }

    return (
      <span className={cn(animation === 'glow' && 'animate-pulse', className)} {...(props as React.HTMLAttributes<HTMLSpanElement>)}>
        {children}
      </span>
    );
  }

  if (as === 'div') {
    return (
      <div className={cn(animation === 'glow' && 'animate-pulse', className)} {...(props as React.HTMLAttributes<HTMLDivElement>)}>
        <Skeleton className="h-4" style={{ width: `${((xs ?? 12) / 12) * 100}%` }} />
      </div>
    );
  }

  return (
    <span className={cn(animation === 'glow' && 'animate-pulse', className)} {...(props as React.HTMLAttributes<HTMLSpanElement>)}>
      <Skeleton className="h-4" style={{ width: `${((xs ?? 12) / 12) * 100}%` }} />
    </span>
  );
}

function BootstrapBadge({ className, variant, children, ...props }: React.ComponentProps<typeof Badge>): React.ReactElement {
  return <Badge className={className} variant={variant} {...props}>{children}</Badge>;
}

export { BootstrapBadge as Badge, ButtonGroup, Col, Container, ListGroup, Nav, Placeholder, Row, Spinner, Table };
