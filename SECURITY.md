# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| latest on `main` | Yes |
| older releases | No |

## Reporting a Vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

Instead, use one of these methods:

1. **GitHub Private Vulnerability Reporting** (preferred):
   Go to [Security Advisories](https://github.com/alexk-dev/golemcore-bot/security/advisories/new) and create a new private report.

2. **Email**: Send details to **alex@kuleshov.tech**

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Affected component (e.g., `ShellTool`, `FileSystemTool`, `InjectionGuard`)
- Potential impact
- Suggested fix (if any)

### Response Timeline

- **Acknowledgement**: within 48 hours
- **Initial assessment**: within 5 business days
- **Fix release**: depends on severity (critical: ASAP, high: 1-2 weeks)

## Security Measures

This project uses multiple layers of protection:

- **CodeQL** analysis on every push and PR (see `.github/workflows/codeql.yml`)
- **Dependabot** alerts and automated security updates
- **Secret scanning** with push protection enabled
- **SpotBugs + PMD** static analysis in CI (`mvn verify -P strict`)
- **Input sanitization** via `InjectionGuard` and `InputSanitizer`
- **Sandboxed tool execution** with path traversal and command injection protection
- **Rate limiting** and **user allowlists** for Telegram access
- **Tool confirmation** workflow for dangerous operations

## Scope

The following are in scope for security reports:

- Prompt injection bypassing `InjectionGuard`
- Path traversal in `FileSystemTool` or `StoragePort`
- Command injection in `ShellTool`
- Authentication/authorization bypasses
- Secrets exposure
- Dependency vulnerabilities (CVEs)

Out of scope:

- Denial of service via LLM API rate limits (upstream provider issue)
- Social engineering attacks on the LLM
- Vulnerabilities in third-party services (Telegram, OpenAI, Anthropic)
