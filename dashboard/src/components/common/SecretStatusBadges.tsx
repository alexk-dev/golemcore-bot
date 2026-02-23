import type { ReactElement } from 'react';
import { Badge } from 'react-bootstrap';

interface SecretStatusBadgesProps {
  hasStoredSecret: boolean;
  willUpdateSecret: boolean;
}

export function SecretStatusBadges({
  hasStoredSecret,
  willUpdateSecret,
}: SecretStatusBadgesProps): ReactElement {
  return (
    <>
      {hasStoredSecret && (
        <Badge bg="success-subtle" text="success">Configured</Badge>
      )}
      {willUpdateSecret && (
        <Badge bg="info-subtle" text="info">Will update on save</Badge>
      )}
    </>
  );
}
