import type { ReactElement } from 'react';
import type { SystemChannelResponse } from '../../api/system';
import type { HookMappingDraft } from '../../api/webhooks';
import { Card, CardContent } from '../ui/card';
import { HookAgentSection } from './HookMappingAgentSection';
import { HookIdentitySection, HookSecuritySection } from './HookMappingFormSections';

interface HookMappingFormProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  onCopyEndpoint: (name: string) => void;
  linkedTelegramUserId?: string | null;
  availableChannels: SystemChannelResponse[];
  channelsLoading: boolean;
}

export function HookMappingForm({
  mapping,
  onChange,
  onCopyEndpoint,
  linkedTelegramUserId,
  availableChannels,
  channelsLoading,
}: HookMappingFormProps): ReactElement {
  return (
    <Card className="webhook-editor-card overflow-hidden border-border/80 bg-card/70">
      <CardContent className="space-y-5 p-4 sm:p-5">
        <HookIdentitySection mapping={mapping} onChange={onChange} onCopyEndpoint={onCopyEndpoint} />
        <HookSecuritySection mapping={mapping} onChange={onChange} />
        <HookAgentSection
          mapping={mapping}
          onChange={onChange}
          linkedTelegramUserId={linkedTelegramUserId}
          availableChannels={availableChannels}
          channelsLoading={channelsLoading}
        />
      </CardContent>
    </Card>
  );
}
