import * as React from 'react';
import { Alert as AlertPrimitive, type AlertProps as AlertPrimitiveProps } from './alert';
import type { BadgeProps as BadgePrimitiveProps } from './badge';
import { Button as ButtonPrimitive, type ButtonProps as ButtonPrimitiveProps } from './button';
import { Card as CardPrimitive, CardContent, CardHeader, CardTitle } from './card';
import { Form, InputGroup } from './form';
import {
  Badge,
  ButtonGroup,
  Col,
  Container,
  ListGroup,
  Nav,
  Placeholder,
  Row,
  Spinner,
  Table,
} from './layout';
import { Modal, Offcanvas } from './overlay';
import { cn } from '../../lib/utils';

function mapButtonVariant(variant?: string): ButtonPrimitiveProps['variant'] {
  if (variant === 'secondary') { return 'secondary'; }
  if (variant === 'danger' || variant === 'outline-danger') { return variant === 'outline-danger' ? 'outline' : 'destructive'; }
  if (variant === 'warning') { return 'warning'; }
  if (variant === 'success') { return 'success'; }
  if (variant === 'link') { return 'link'; }
  return 'default';
}

function mapBadgeVariant(bg?: string, text?: string): BadgePrimitiveProps['variant'] {
  if (bg === 'success' || bg === 'success-subtle') { return 'success'; }
  if (bg === 'warning') { return 'warning'; }
  if (bg === 'danger') { return 'destructive'; }
  if (bg === 'info' || bg === 'info-subtle') { return 'info'; }
  if (bg === 'light' && text === 'dark') { return 'light'; }
  if (bg === 'primary') { return 'default'; }
  return 'secondary';
}

function mapAlertVariant(variant?: string): AlertPrimitiveProps['variant'] {
  if (variant === 'danger') { return 'danger'; }
  if (variant === 'warning') { return 'warning'; }
  if (variant === 'info') { return 'info'; }
  if (variant === 'secondary') { return 'secondary'; }
  return 'default';
}

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: string;
  size?: 'sm' | 'lg';
}

function Button({ className, variant, size, ...props }: ButtonProps): React.ReactElement {
  return (
    <ButtonPrimitive
      className={cn('btn', size === 'sm' && 'btn-sm', size === 'lg' && 'btn-lg', className)}
      variant={mapButtonVariant(variant)}
      size={size === 'sm' ? 'sm' : size === 'lg' ? 'lg' : 'default'}
      {...props}
    />
  );
}

interface CardComponent extends React.FC<React.HTMLAttributes<HTMLDivElement>> {
  Body: typeof CardBody;
  Header: typeof CardHeaderShim;
  Title: typeof CardTitleShim;
  Text: typeof CardText;
}

function CardBody({ className, ...props }: React.HTMLAttributes<HTMLDivElement>): React.ReactElement {
  return <CardContent className={cn('card-body', className)} {...props} />;
}

function CardHeaderShim({ className, ...props }: React.HTMLAttributes<HTMLDivElement>): React.ReactElement {
  return <CardHeader className={cn('card-header', className)} {...props} />;
}

function CardTitleShim({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>): React.ReactElement {
  return <CardTitle className={cn('card-title', className)} {...props} />;
}

function CardText({ className, ...props }: React.HTMLAttributes<HTMLParagraphElement>): React.ReactElement {
  return <p className={cn('card-text', className)} {...props} />;
}

const BaseCard: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({ className, ...props }) => (
  <CardPrimitive className={cn('card', className)} {...props} />
);

const Card = BaseCard as CardComponent;
Card.Body = CardBody;
Card.Header = CardHeaderShim;
Card.Title = CardTitleShim;
Card.Text = CardText;

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  bg?: string;
  text?: string;
  pill?: boolean;
}

function TailwindBadge({ className, bg, text, pill, ...props }: BadgeProps): React.ReactElement {
  return <Badge className={cn('badge', pill && 'rounded-circle', className)} variant={mapBadgeVariant(bg, text)} {...props} />;
}

interface AlertProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: string;
}

function Alert({ className, variant, ...props }: AlertProps): React.ReactElement {
  return <AlertPrimitive className={cn('alert', className)} variant={mapAlertVariant(variant)} {...props} />;
}

interface ProgressBarProps extends React.HTMLAttributes<HTMLDivElement> {
  now?: number;
  variant?: string;
}

function progressVariantClassName(variant?: string): string {
  if (variant === 'success') { return 'bg-green-500'; }
  if (variant === 'warning') { return 'bg-amber-500'; }
  if (variant === 'danger') { return 'bg-red-500'; }
  return 'bg-primary';
}

function ProgressBar({ className, now = 0, variant, ...props }: ProgressBarProps): React.ReactElement {
  const value = Math.max(0, Math.min(100, now));
  return (
    <div
      className={cn('h-2 overflow-hidden rounded-full bg-muted', className)}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={value}
      {...props}
    >
      <div
        className={cn('h-full rounded-full transition-[width] duration-300', progressVariantClassName(variant))}
        style={{ width: `${value}%` }}
      />
    </div>
  );
}

export {
  Alert,
  TailwindBadge as Badge,
  Button,
  ButtonGroup,
  Card,
  Col,
  Container,
  Form,
  InputGroup,
  ListGroup,
  Modal,
  Nav,
  Offcanvas,
  Placeholder,
  ProgressBar,
  Row,
  Spinner,
  Table,
};
