import { Card, Row, Col, Badge, Spinner } from 'react-bootstrap';
import { useSkills } from '../hooks/useSkills';

export default function SkillsPage() {
  const { data: skills, isLoading } = useSkills();

  if (isLoading) return <Spinner />;

  return (
    <div>
      <h4 className="mb-4">Skills</h4>
      <Row className="g-3">
        {skills?.map((skill) => (
          <Col key={skill.name} md={4}>
            <Card className="h-100">
              <Card.Body>
                <div className="d-flex justify-content-between align-items-start">
                  <Card.Title className="h6">{skill.name}</Card.Title>
                  <div>
                    <Badge bg={skill.available ? 'success' : 'secondary'}>
                      {skill.available ? 'Available' : 'Unavailable'}
                    </Badge>
                    {skill.hasMcp && (
                      <Badge bg="info" className="ms-1">MCP</Badge>
                    )}
                  </div>
                </div>
                <Card.Text className="text-muted small">
                  {skill.description || 'No description'}
                </Card.Text>
                {skill.modelTier && (
                  <div className="small text-muted">
                    Tier: <Badge bg="outline-primary" text="dark">{skill.modelTier}</Badge>
                  </div>
                )}
              </Card.Body>
            </Card>
          </Col>
        ))}
        {skills?.length === 0 && (
          <Col>
            <Card className="text-center text-muted py-5">
              <Card.Body>No skills loaded</Card.Body>
            </Card>
          </Col>
        )}
      </Row>
    </div>
  );
}
