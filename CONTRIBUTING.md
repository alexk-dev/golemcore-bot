# Contributing to GolemCore Bot

Thank you for your interest in contributing! This document provides guidelines and instructions for contributing.

## 📋 Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Code Quality Standards](#code-quality-standards)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)
- [License](#license)

---

## Code of Conduct

Be respectful, inclusive, and professional. We're all here to build something great together.

---

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.x
- Git
- At least one LLM API key (OpenAI or Anthropic)

### Fork and Clone

```bash
# Fork the repository on GitHub, then:
git clone https://github.com/YOUR_USERNAME/golemcore-bot.git
cd golemcore-bot

# Add upstream remote
git remote add upstream https://github.com/ORIGINAL_OWNER/golemcore-bot.git
```

### Install Pre-commit Hooks

```bash
pip install pre-commit    # one-time
pre-commit install        # install hooks for this repo
```

This installs pre-commit hooks (PMD, trailing whitespace, YAML/JSON validation, etc.) that run before each commit. See `.pre-commit-config.yaml` for the full list.

---

## Development Workflow

### 1. Create a Branch

```bash
git checkout -b feature/your-feature-name
# or
git checkout -b fix/bug-description
```

**Branch naming:**
- `feature/` — New features
- `fix/` — Bug fixes
- `refactor/` — Code refactoring
- `docs/` — Documentation updates
- `test/` — Test additions/improvements

### 2. Make Changes

- Follow existing code style
- Add tests for new features
- Update documentation as needed
- Keep commits focused and atomic

### 3. Run Quality Checks

```bash
# Run all checks (strict mode — fails on violations)
mvn clean verify -P strict

# Or individual checks
mvn test              # Unit tests
mvn pmd:check         # PMD static analysis
mvn spotbugs:check    # SpotBugs bug detection
```

### 4. Commit

```bash
git add <files>
git commit -m "Brief description of changes"
```

**Commit messages:**
- Use imperative mood ("Add feature" not "Added feature")
- First line: brief summary (50 chars max)
- Blank line, then detailed description if needed
- Reference issues: "Fixes #123" or "Closes #456"

**Example:**
```
Add skill pipeline auto-transition feature

Implements automatic skill transitions based on nextSkill field.
Adds SkillPipelineSystem (order=55) that runs after tool execution.

Fixes #123
```

### 5. Push and Create PR

```bash
git push origin feature/your-feature-name
```

Then create a Pull Request on GitHub.

---

## Code Quality Standards

### Code Style

- **Java conventions:** Follow standard Java naming conventions
- **Indentation:** 4 spaces (no tabs)
- **Line length:** Max 120 characters
- **Imports:** No wildcards, organize logically
- **Lombok:** Use `@Data`, `@Builder`, `@Slf4j` where appropriate
- **JavaDoc:** Public APIs must have JavaDoc

**Example:**
```java
/**
 * Service for managing conversation sessions.
 */
@Slf4j
@Service
public class SessionService {

    private final StoragePort storage;
    private final MemoryComponent memory;

    /**
     * Creates a new session for the given channel.
     *
     * @param chatId the chat identifier
     * @return the created session
     */
    public AgentSession createSession(String chatId) {
        log.info("Creating new session for chat: {}", chatId);
        // ...
    }
}
```

### Static Analysis

All code must pass:
- ✅ **PMD** — Error-prone patterns, code smells
- ✅ **SpotBugs** — Bug detection (Medium+ priority)
- ✅ **Compiler warnings** — No compiler warnings

**Strict mode (CI/CD):**
```bash
mvn clean verify -P strict
```

### Design Principles

1. **Component-based design** — Implement `Component` interface for modular functionality
2. **Interface-based ports** — External dependencies through port interfaces
3. **Ordered systems** — Use `@Order` annotation for pipeline systems
4. **Lazy initialization** — All beans exist; use `isEnabled()` for runtime checks
5. **Fail-safe defaults** — Graceful degradation when optional features unavailable

---

## Testing

### Writing Tests

- **Unit tests** — Test individual classes in isolation
- **Integration tests** — Test component interactions
- **Mock external dependencies** — Use Mockito for LLM, storage, etc.
- **Test naming:** `shouldDoSomethingWhenCondition()`

**Example:**
```java
@Test
void shouldReturnActiveSkillWhenMatchFound() {
    // Given
    Message message = Message.builder()
        .content("Hello bot")
        .build();

    // When
    SkillMatchResult result = skillMatcher.match(message);

    // Then
    assertThat(result.isMatched()).isTrue();
    assertThat(result.getSkill().getName()).isEqualTo("greeting");
}
```

### Running Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=SessionServiceTest

# Specific test method
mvn test -Dtest=SessionServiceTest#shouldCreateNewSession

# With coverage
mvn test jacoco:report
```

### Coverage Requirements

- **Minimum:** 70% line coverage
- **Target:** 80%+ for new code
- **Critical paths:** 90%+ coverage (session management, tool execution)

---

## Submitting Changes

### Pull Request Checklist

Before submitting a PR, ensure:

- ✅ All tests pass (`mvn test`)
- ✅ Code quality checks pass (`mvn clean verify -P strict`)
- ✅ No compiler warnings
- ✅ New features have tests
- ✅ Documentation updated (README, CLAUDE.md if needed)
- ✅ Commit messages are descriptive
- ✅ Branch is up-to-date with upstream main

### PR Description Template

```markdown
## Description
Brief description of changes.

## Type of Change
- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to change)
- [ ] Documentation update

## Testing
Describe how you tested your changes.

## Checklist
- [ ] Tests pass locally
- [ ] Code quality checks pass
- [ ] Documentation updated
- [ ] No breaking changes (or documented if unavoidable)

Fixes #(issue number)
```

### Review Process

1. **Automated checks** — GitHub Actions runs tests and quality checks
2. **Code review** — Maintainer reviews code, suggests changes
3. **Revisions** — Address feedback, push updates
4. **Approval** — Maintainer approves PR
5. **Merge** — Maintainer merges to main branch

---

## License

By contributing, you agree to license your contributions under the **Apache License 2.0**.

### Patent Grant

By submitting code, you automatically grant:
- **Copyright license** (Section 2) for your code
- **Patent license** (Section 3) for any patents in your contribution

See [LICENSE](LICENSE) for full details.

### What This Means

✅ **You retain copyright** of your contributions
✅ **You grant others the right** to use, modify, and distribute your code under Apache 2.0
✅ **You grant patent rights** for your contributions (defensive patent protection)
❌ **If you sue the project** for patent infringement, your patent license terminates

---

## Questions?

- **Issues:** Open a GitHub issue for bugs or feature requests
- **Discussions:** Use GitHub Discussions for questions and ideas
- **Security:** Report security issues via email (not public issues)

Thank you for contributing! 🎉
