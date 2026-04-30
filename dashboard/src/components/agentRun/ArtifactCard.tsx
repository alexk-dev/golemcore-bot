import { FiDownload, FiFileText } from 'react-icons/fi';
import type { ArtifactViewModel } from './types';

interface ArtifactCardProps {
  artifact: ArtifactViewModel;
  onOpen?: (id: string) => void;
}

export default function ArtifactCard({ artifact, onOpen }: ArtifactCardProps) {
  return (
    <article className="artifact-card" aria-label={`Artifact ${artifact.name}`}>
      <span className="artifact-card__icon" aria-hidden="true"><FiFileText size={16} /></span>
      <div className="artifact-card__body">
        <span className="artifact-card__title">{artifact.name}</span>
        {artifact.description != null && (
          <span className="artifact-card__description">{artifact.description}</span>
        )}
      </div>
      {artifact.href != null && (
        <a className="agent-btn" href={artifact.href} target="_blank" rel="noreferrer">
          <FiDownload size={14} aria-hidden="true" />
          <span>Open</span>
        </a>
      )}
      {artifact.href == null && onOpen != null && (
        <button type="button" className="agent-btn" onClick={() => onOpen(artifact.id)}>
          <FiDownload size={14} aria-hidden="true" />
          <span>Open</span>
        </button>
      )}
    </article>
  );
}
