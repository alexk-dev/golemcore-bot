#!/usr/bin/env python3
"""Fail CI if changed files contain Cyrillic characters.

Scope is intentionally limited to files changed in the PR (or push range),
so existing repository content is not blocked retroactively.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path
from typing import List

CYRILLIC_PATTERN = re.compile(r"[\u0400-\u04FF]")
TEXT_EXTENSIONS = {
    ".java",
    ".kt",
    ".kts",
    ".groovy",
    ".xml",
    ".yml",
    ".yaml",
    ".md",
    ".txt",
    ".properties",
    ".json",
    ".ts",
    ".tsx",
    ".js",
    ".jsx",
    ".css",
    ".scss",
    ".html",
    ".sql",
    ".sh",
    ".py",
}


def run_git_command(args: List[str]) -> str:
    result = subprocess.run(["git", *args], check=True, capture_output=True, text=True)
    return result.stdout.strip()


def resolve_diff_range() -> str:
    event_name = os.getenv("GITHUB_EVENT_NAME", "")

    if event_name == "pull_request":
        base_ref = os.getenv("GITHUB_BASE_REF", "")
        if base_ref:
            base_branch = f"origin/{base_ref}"
            return f"{base_branch}...HEAD"

    before_sha = os.getenv("GITHUB_EVENT_BEFORE", "")
    github_sha = os.getenv("GITHUB_SHA", "")

    if before_sha and before_sha != "0000000000000000000000000000000000000000" and github_sha:
        return f"{before_sha}...{github_sha}"

    return "HEAD~1...HEAD"


def list_changed_files(diff_range: str) -> List[Path]:
    output = run_git_command(["diff", "--name-only", diff_range])
    if not output:
        return []

    changed_paths = []
    for raw in output.splitlines():
        if not raw:
            continue
        path = Path(raw)
        if not path.exists() or path.is_dir():
            continue
        if path.suffix.lower() in TEXT_EXTENSIONS:
            changed_paths.append(path)

    return changed_paths


def scan_file(path: Path) -> List[str]:
    violations = []
    try:
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return violations

    for index, line in enumerate(lines, start=1):
        if CYRILLIC_PATTERN.search(line):
            preview = line.strip()
            if len(preview) > 180:
                preview = preview[:177] + "..."
            violations.append(f"{path}:{index}: {preview}")

    return violations


def main() -> int:
    diff_range = resolve_diff_range()
    changed_files = list_changed_files(diff_range)

    if not changed_files:
        print("No changed text files to scan.")
        return 0

    all_violations = []
    for file_path in changed_files:
        all_violations.extend(scan_file(file_path))

    if all_violations:
        print("Cyrillic characters detected in changed files:")
        for violation in all_violations:
            print(f"- {violation}")
        print("Use English for PR descriptions, code comments, logs, and user-facing text in changed files.")
        return 1

    print("No Cyrillic characters detected in changed files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
