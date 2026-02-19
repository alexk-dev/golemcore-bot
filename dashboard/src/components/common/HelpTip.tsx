import { type ReactElement, useId } from 'react';
import { OverlayTrigger, Tooltip } from 'react-bootstrap';
import { FiHelpCircle } from 'react-icons/fi';

interface HelpTipProps {
  text: string;
}

export default function HelpTip({ text }: HelpTipProps): ReactElement {
  const tooltipId = useId();

  return (
    <OverlayTrigger
      placement="top"
      trigger={['hover', 'focus']}
      overlay={<Tooltip id={tooltipId}>{text}</Tooltip>}
    >
      <button
        type="button"
        className="setting-tip setting-tip-btn"
        aria-label={text}
      >
        <FiHelpCircle aria-hidden="true" focusable="false" />
      </button>
    </OverlayTrigger>
  );
}
