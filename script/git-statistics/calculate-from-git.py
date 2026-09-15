#!/usr/bin/env python3
"""Recompute per-commit churn and files touched from Git repositories.

The default ``capped`` scope targets commits whose cached GitHub REST response
contains 300 files. Use ``--scope all`` for a complete independent audit. The
default targeted clone mode fetches only observed commits and their parents.

Results are appended to JSON Lines as soon as each commit is processed, making
the run resumable. Existing successful rows are skipped; use ``--retry-errors``
to retry rows that previously failed because a repository was unavailable.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import subprocess
import sys
import threading
from collections import defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Iterable


DEFAULT_START = "2025-11-01"
DEFAULT_END = "2026-04-30"
REPOSITORY_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
REPOSITORY_URL_RE = re.compile(r"/repos/([^/]+/[^/]+)/commits/")


@dataclass(frozen=True)
class Observation:
    developer: str
    day: str
    repository: str
    sha: str


class GitFailure(RuntimeError):
    """A Git command failed."""


def repository_root(start: Path | None = None) -> Path:
    current = (start or Path.cwd()).resolve()
    for candidate in (current, *current.parents):
        if (candidate / "developers.json").is_file() and (candidate / "data").is_dir():
            return candidate
    raise FileNotFoundError("could not locate developers.json and data/")


def repository_from_commit(commit: dict) -> str | None:
    repository = commit.get("repository") or {}
    if repository.get("full_name"):
        return repository["full_name"]
    match = REPOSITORY_URL_RE.search(commit.get("url", ""))
    return match.group(1) if match else None


def cached_file_count(cache_dir: Path, sha: str) -> int | None:
    path = cache_dir / f"{sha}.json"
    if not path.is_file():
        return None
    try:
        detail = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return None
    files = detail if isinstance(detail, list) else detail.get("files", [])
    return len(files) if isinstance(files, list) else None


def load_observations(
    root: Path,
    start_date: str,
    end_date: str,
    scope: str,
    developers_filter: set[str],
    repositories_filter: set[str],
) -> list[Observation]:
    registered = {
        item["handle"] for item in json.loads((root / "developers.json").read_text())
    }
    if developers_filter:
        unknown = developers_filter - registered
        if unknown:
            raise ValueError(f"unknown developer(s): {', '.join(sorted(unknown))}")
        registered &= developers_filter

    cache_dir = root / "data" / "commits"
    seen: set[tuple[str, str]] = set()
    observations: list[Observation] = []

    for path in sorted((root / "data").glob("*.json")):
        snapshot = json.loads(path.read_text())
        developer = snapshot.get("developer")
        if developer not in registered or not isinstance(snapshot.get("days"), dict):
            continue
        for day, values in snapshot["days"].items():
            if not start_date <= day <= end_date:
                continue
            for commit in values.get("commits", []):
                sha = commit.get("sha", "")
                key = (developer, sha)
                if not sha or key in seen:
                    continue
                seen.add(key)
                repository = repository_from_commit(commit)
                if not repository or not REPOSITORY_RE.fullmatch(repository):
                    continue
                if repositories_filter and repository.lower() not in repositories_filter:
                    continue
                if scope == "capped":
                    count = cached_file_count(cache_dir, sha)
                    if count is None or count < 300:
                        continue
                observations.append(Observation(developer, day, repository, sha))

    return observations


def git_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_TERMINAL_PROMPT": "0",
        }
    )
    return environment


def run_git(
    arguments: list[str], cwd: Path | None = None, check: bool = True
) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        ["git", *arguments],
        cwd=cwd,
        env=git_environment(),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode:
        command = "git " + " ".join(arguments[:4])
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise GitFailure(f"{command} failed ({result.returncode}): {message}")
    return result


def clone_path(cache_root: Path, repository: str) -> Path:
    owner, name = repository.split("/", 1)
    return cache_root / owner.lower() / f"{name.lower()}.git"


def ensure_clone(
    cache_root: Path, repository: str, clone_mode: str, refresh: bool
) -> Path:
    destination = clone_path(cache_root, repository)
    url = f"https://github.com/{repository}.git"
    if not destination.is_dir():
        destination.parent.mkdir(parents=True, exist_ok=True)
        if clone_mode == "targeted":
            run_git(["init", "--bare", str(destination)])
            run_git(["remote", "add", "origin", url], cwd=destination)
            run_git(["config", "gc.auto", "0"], cwd=destination)
        else:
            arguments = ["clone", "--mirror"]
            if clone_mode == "partial":
                arguments.append("--filter=blob:none")
            arguments.extend([url, str(destination)])
            run_git(arguments)
    elif not (destination / "HEAD").exists():
        raise GitFailure(f"cache path is not a bare Git repository: {destination}")

    # Keep the original spelling/redirect target current for explicit SHA fetches.
    run_git(["remote", "set-url", "origin", url], cwd=destination)
    if clone_mode == "targeted":
        run_git(["config", "remote.origin.promisor", "true"], cwd=destination)
        run_git(
            ["config", "remote.origin.partialclonefilter", "blob:none"],
            cwd=destination,
        )
    if refresh and clone_mode != "targeted":
        arguments = ["remote", "update", "--prune"]
        run_git(arguments, cwd=destination)
    return destination


def commit_exists(repository_path: Path, sha: str) -> bool:
    return (
        run_git(["cat-file", "-e", f"{sha}^{{commit}}"], cwd=repository_path, check=False).returncode
        == 0
    )


def ensure_commit(repository_path: Path, sha: str, clone_mode: str) -> None:
    if commit_exists(repository_path, sha):
        return
    arguments = ["fetch", "--no-tags"]
    if clone_mode == "partial":
        arguments.append("--filter=blob:none")
    arguments.extend(["origin", sha])
    run_git(arguments, cwd=repository_path)
    if not commit_exists(repository_path, sha):
        raise GitFailure(f"commit {sha} is unavailable after fetch")


def chunks(values: list[str], size: int) -> Iterable[list[str]]:
    for index in range(0, len(values), size):
        yield values[index : index + size]


def fetch_targeted_batch(repository_path: Path, shas: list[str]) -> None:
    # Depth two includes each selected commit and its first parent. Local audit
    # refs keep fetched objects reachable across resumptions and later git gc.
    refspecs = [f"+{sha}:refs/audit/{sha}" for sha in shas]
    run_git(
        [
            "fetch",
            "--no-tags",
            "--depth=2",
            "--filter=blob:none",
            "origin",
            *refspecs,
        ],
        cwd=repository_path,
    )


def prepare_targeted_commits(
    repository_path: Path, shas: Iterable[str], batch_size: int
) -> dict[str, str]:
    missing = sorted({sha for sha in shas if not commit_exists(repository_path, sha)})
    errors: dict[str, str] = {}
    for batch in chunks(missing, batch_size):
        try:
            fetch_targeted_batch(repository_path, batch)
        except Exception:
            # One inaccessible SHA must not discard the rest of a batch.
            for sha in batch:
                try:
                    fetch_targeted_batch(repository_path, [sha])
                except Exception as error:
                    errors[sha] = str(error)
        for sha in batch:
            if sha not in errors and not commit_exists(repository_path, sha):
                errors[sha] = f"commit {sha} is unavailable after targeted fetch"
    return errors


def parse_numstat(output: bytes) -> dict[str, int]:
    """Parse ``git diff-tree --numstat -z`` including rename records."""
    fields = output.split(b"\0")
    if fields and fields[-1] == b"":
        fields.pop()

    additions = deletions = files_touched = binary_files = renames = 0
    index = 0
    while index < len(fields):
        record = fields[index]
        index += 1
        parts = record.split(b"\t", 2)
        if len(parts) != 3:
            raise ValueError(f"unexpected numstat record: {record!r}")
        added_raw, deleted_raw, path = parts
        if path == b"":
            if index + 1 >= len(fields):
                raise ValueError("incomplete numstat rename record")
            # The following fields are the old and new path. A rename is one touch.
            index += 2
            renames += 1
        if added_raw == b"-" or deleted_raw == b"-":
            binary_files += 1
        else:
            additions += int(added_raw)
            deletions += int(deleted_raw)
        files_touched += 1

    return {
        "additions": additions,
        "deletions": deletions,
        "churn": additions + deletions,
        "files_touched": files_touched,
        "binary_files": binary_files,
        "renames_detected": renames,
    }


def calculate_commit(repository_path: Path, sha: str) -> dict:
    revision = run_git(["rev-list", "--parents", "-n", "1", sha], cwd=repository_path)
    fields = revision.stdout.decode("ascii").strip().split()
    if not fields or fields[0] != sha:
        raise GitFailure(f"could not resolve commit {sha}")
    parents = fields[1:]

    arguments = [
        "diff-tree",
        "--no-commit-id",
        "-r",
        "--numstat",
        "-z",
        "--find-renames=50%",
        "--no-ext-diff",
        "--diff-algorithm=myers",
    ]
    if parents:
        arguments.extend([parents[0], sha])
        parent_sha = parents[0]
    else:
        arguments.extend(["--root", sha])
        parent_sha = None

    statistics = parse_numstat(run_git(arguments, cwd=repository_path).stdout)
    statistics.update(
        {
            "parent_sha": parent_sha,
            "parent_count": len(parents),
            "diff_policy": "root-or-first-parent",
            "rename_policy": "git-find-renames-50-percent",
        }
    )
    return statistics


def result_key(item: dict) -> tuple[str, str, str]:
    return item.get("developer", ""), item.get("repository", ""), item.get("sha", "")


def load_completed(path: Path, retry_errors: bool) -> set[tuple[str, str, str]]:
    completed: set[tuple[str, str, str]] = set()
    if not path.is_file():
        return completed
    for line_number, line in enumerate(path.read_text().splitlines(), start=1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid JSON on {path}:{line_number}: {error}") from error
        if item.get("status") == "ok" or not retry_errors:
            completed.add(result_key(item))
    return completed


def append_result(
    stream, write_lock: threading.Lock, observation: Observation, status: str, **values
) -> None:
    item = {
        "developer": observation.developer,
        "day": observation.day,
        "repository": observation.repository,
        "sha": observation.sha,
        "commit_url": f"https://github.com/{observation.repository}/commit/{observation.sha}",
        "status": status,
        "computed_at": datetime.now(UTC).isoformat(),
        **values,
    }
    with write_lock:
        stream.write(json.dumps(item, sort_keys=True) + "\n")
        stream.flush()


def grouped_by_repository(
    observations: Iterable[Observation],
) -> dict[str, list[Observation]]:
    grouped: dict[str, list[Observation]] = defaultdict(list)
    for observation in observations:
        grouped[observation.repository].append(observation)
    return dict(sorted(grouped.items(), key=lambda item: item[0].lower()))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start-date", default=DEFAULT_START)
    parser.add_argument("--end-date", default=DEFAULT_END)
    parser.add_argument(
        "--scope",
        choices=("capped", "all"),
        default="capped",
        help="capped: cached file list has >=300 entries; all: every observation",
    )
    parser.add_argument("--developer", action="append", default=[], help="repeatable")
    parser.add_argument("--repository", action="append", default=[], help="OWNER/REPO; repeatable")
    parser.add_argument(
        "--clone-mode",
        choices=("targeted", "partial", "full"),
        default="targeted",
        help=(
            "targeted fetches only selected commits plus parents; partial/full "
            "mirror every ref"
        ),
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=4,
        help="repositories processed concurrently (default: 4)",
    )
    parser.add_argument(
        "--fetch-batch-size",
        type=int,
        default=128,
        help="selected SHAs fetched per targeted request (default: 128)",
    )
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--refresh", action="store_true", help="refresh existing mirrors")
    parser.add_argument("--retry-errors", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.start_date > args.end_date:
        raise SystemExit("--start-date must not be later than --end-date")
    if args.jobs < 1:
        raise SystemExit("--jobs must be at least 1")
    if args.fetch_batch_size < 1:
        raise SystemExit("--fetch-batch-size must be at least 1")

    root = repository_root()
    cache_root = (args.cache_dir or root / "script/git-statistics/.cache/repos").resolve()
    output_path = (
        args.output or root / "script/git-statistics/output/git-diff-statistics.jsonl"
    ).resolve()
    repository_filter = {item.lower() for item in args.repository}
    observations = load_observations(
        root,
        args.start_date,
        args.end_date,
        args.scope,
        set(args.developer),
        repository_filter,
    )
    grouped = grouped_by_repository(observations)

    print(
        f"scope={args.scope}: {len(observations):,} developer/SHA observations "
        f"across {len(grouped):,} repositories"
    )
    print(f"repository cache: {cache_root}")
    print(f"results: {output_path}")
    if args.dry_run:
        for repository, items in sorted(
            grouped.items(), key=lambda item: (-len(item[1]), item[0].lower())
        ):
            print(f"{len(items):6,d}  {repository}")
        return 0

    output_path.parent.mkdir(parents=True, exist_ok=True)
    completed = load_completed(output_path, args.retry_errors)
    pending = [item for item in observations if result_key(item.__dict__) not in completed]
    grouped = grouped_by_repository(pending)
    print(f"already recorded: {len(observations) - len(pending):,}; pending: {len(pending):,}")

    def process_repository(
        repo_index: int,
        repository: str,
        items: list[Observation],
        stream,
        write_lock: threading.Lock,
        print_lock: threading.Lock,
    ) -> tuple[int, int]:
        local_successes = local_failures = 0
        with print_lock:
            print(
                f"[{repo_index}/{len(grouped)}] {repository}: {len(items):,} commits",
                flush=True,
            )
        try:
            repository_path = ensure_clone(
                cache_root, repository, args.clone_mode, args.refresh
            )
        except Exception as error:
            for observation in items:
                append_result(stream, write_lock, observation, "error", error=str(error))
                local_failures += 1
            return local_successes, local_failures

        preparation_errors: dict[str, str] = {}
        if args.clone_mode == "targeted":
            preparation_errors = prepare_targeted_commits(
                repository_path,
                (observation.sha for observation in items),
                args.fetch_batch_size,
            )

        statistics_by_sha: dict[str, dict] = {}
        error_by_sha = preparation_errors.copy()
        for observation in items:
            if observation.sha not in statistics_by_sha and observation.sha not in error_by_sha:
                try:
                    if args.clone_mode != "targeted":
                        ensure_commit(repository_path, observation.sha, args.clone_mode)
                    statistics_by_sha[observation.sha] = calculate_commit(
                        repository_path, observation.sha
                    )
                except Exception as error:
                    error_by_sha[observation.sha] = str(error)

            if observation.sha in statistics_by_sha:
                append_result(
                    stream,
                    write_lock,
                    observation,
                    "ok",
                    **statistics_by_sha[observation.sha],
                )
                local_successes += 1
            else:
                append_result(
                    stream,
                    write_lock,
                    observation,
                    "error",
                    error=error_by_sha[observation.sha],
                )
                local_failures += 1
        return local_successes, local_failures

    successes = failures = 0
    write_lock = threading.Lock()
    print_lock = threading.Lock()
    with output_path.open("a", encoding="utf-8") as stream:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
            futures = [
                executor.submit(
                    process_repository,
                    repo_index,
                    repository,
                    items,
                    stream,
                    write_lock,
                    print_lock,
                )
                for repo_index, (repository, items) in enumerate(grouped.items(), start=1)
            ]
            for future in concurrent.futures.as_completed(futures):
                local_successes, local_failures = future.result()
                successes += local_successes
                failures += local_failures

    print(f"finished: {successes:,} successful, {failures:,} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
