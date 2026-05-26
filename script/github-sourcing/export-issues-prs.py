#!/usr/bin/env python3
"""Export GitHub issues/PRs (with metadata and discussions) of specific repo via gh CLI.

Outputs:
    - data/<owner>/<repo>/issues.json
    - data/<owner>/<repo>/prs.json

Important flags:
    - --type: Choose export target (issues, prs, both).
    - --state: Filter by open, closed, or all.
    - --output-root: Change where output files are written.
    - --verbose: Print progress details to stderr.

Examples:
    - python script/github-sourcing/export-issues-prs.py owner/repo
    - python script/github-sourcing/export-issues-prs.py owner/repo --type issues --state closed
    - python script/github-sourcing/export-issues-prs.py owner/repo --output-root script/github-sourcing/verify-output --verbose
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from collections.abc import Iterable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "data"
DEFAULT_CHECKPOINT_ROOT = REPO_ROOT / "script" / "github-sourcing" / "cache"
DEFAULT_MAX_RETRIES = 5
MIN_RATE_LIMIT_WAIT_SECONDS = 5 * 60
MAX_RATE_LIMIT_WAIT_SECONDS = 2 * 60 * 60
GH_MAX_RETRIES = DEFAULT_MAX_RETRIES


class GhError(RuntimeError):
    """Raised when gh command execution fails."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Export issues and pull requests with complete metadata and "
            "discussion threads using GitHub CLI."
        )
    )
    parser.add_argument(
        "repo",
        help="Repository in owner/repo format.",
    )
    parser.add_argument(
        "--state",
        default="all",
        choices=["open", "closed", "all"],
        help="Issue/PR state filter (default: all).",
    )
    parser.add_argument(
        "--type",
        default="both",
        choices=["issues", "prs", "both"],
        help="Export target type (default: both).",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=DEFAULT_OUTPUT_ROOT,
        help=(
            "Root output directory (default: data). "
            "Files are written to <root>/<owner>/<repo>/issues.json and prs.json."
        ),
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Print progress information to stderr.",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=DEFAULT_MAX_RETRIES,
        help="Maximum retries for transient/rate-limit failures (default: 5).",
    )
    parser.add_argument(
        "--checkpoint-root",
        type=Path,
        default=DEFAULT_CHECKPOINT_ROOT,
        help=argparse.SUPPRESS,
    )
    return parser.parse_args()


def log(verbose: bool, message: str) -> None:
    if verbose:
        print(message, file=sys.stderr)


def ensure_repo_format(repo: str) -> tuple[str, str]:
    parts = repo.split("/")
    if len(parts) != 2 or not parts[0] or not parts[1]:
        raise ValueError("Repository must be provided as owner/repo.")
    return parts[0], parts[1]


def run_gh_json(
    endpoint: str,
    *,
    paginate: bool = False,
    verbose: bool = False,
) -> Any:
    cmd = [
        "gh",
        "api",
        endpoint,
        "--header",
        "Accept: application/vnd.github+json",
        "--header",
        "X-GitHub-Api-Version: 2022-11-28",
    ]
    if paginate:
        cmd.extend(["--paginate", "--slurp"])

    retries = GH_MAX_RETRIES
    for attempt in range(retries + 1):
        log(verbose, f"Running: {' '.join(cmd)}")
        proc = subprocess.run(cmd, capture_output=True, text=False)
        if proc.returncode == 0:
            stdout = (proc.stdout or b"").decode("utf-8", errors="replace").strip()
            if not stdout:
                return [] if paginate else None
            try:
                return json.loads(stdout)
            except json.JSONDecodeError as exc:
                raise GhError(f"Failed to parse JSON for '{endpoint}': {exc}") from exc

        stderr = (proc.stderr or b"").decode("utf-8", errors="replace").strip()
        if attempt >= retries:
            raise GhError(f"gh api failed for '{endpoint}': {stderr}")

        wait_seconds = retry_wait_seconds(stderr, attempt=attempt, verbose=verbose)
        log(
            verbose,
            f"Retrying '{endpoint}' in {wait_seconds}s (attempt {attempt + 1}/{retries})...",
        )
        time.sleep(wait_seconds)

    raise GhError(f"gh api failed for '{endpoint}': exhausted retries")


def query_core_rate_limit_reset() -> tuple[int | None, int | None]:
    cmd = [
        "gh",
        "api",
        "rate_limit",
        "--header",
        "Accept: application/vnd.github+json",
        "--header",
        "X-GitHub-Api-Version: 2022-11-28",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=False)
    if proc.returncode != 0:
        return None, None
    stdout = (proc.stdout or b"").decode("utf-8", errors="replace").strip()
    if not stdout:
        return None, None
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError:
        return None, None
    if not isinstance(payload, dict):
        return None, None
    resources = payload.get("resources")
    if not isinstance(resources, dict):
        return None, None
    core = resources.get("core")
    if not isinstance(core, dict):
        return None, None
    reset = core.get("reset")
    remaining = core.get("remaining")
    return (reset if isinstance(reset, int) else None, remaining if isinstance(remaining, int) else None)


def retry_wait_seconds(stderr: str, *, attempt: int, verbose: bool) -> int:
    _ = verbose
    lowered = stderr.lower()
    if "rate limit" in lowered:
        exponential_wait = min(
            MAX_RATE_LIMIT_WAIT_SECONDS,
            MIN_RATE_LIMIT_WAIT_SECONDS * (2 ** max(0, attempt)),
        )

        if "secondary rate limit" in lowered:
            return exponential_wait

        reset_ts, remaining = query_core_rate_limit_reset()
        reset_wait = None
        if reset_ts is not None:
            reset_wait = max(0, reset_ts - int(time.time()) + 2)

        # If we're seeing a hard 403 even with positive remaining, fall back to exponential wait.
        if remaining is not None and remaining > 0:
            return exponential_wait

        if reset_wait is None:
            return exponential_wait

        return min(
            MAX_RATE_LIMIT_WAIT_SECONDS,
            max(MIN_RATE_LIMIT_WAIT_SECONDS, reset_wait, exponential_wait),
        )

    # Generic backoff for other failures.
    if "timeout" in lowered or "temporar" in lowered:
        return 10
    return 5


def run_gh_text(args: list[str]) -> str:
    proc = subprocess.run(args, capture_output=True, text=False)
    if proc.returncode != 0:
        stderr = (proc.stderr or b"").decode("utf-8", errors="replace").strip()
        raise GhError(f"Command failed: {' '.join(args)}\n{stderr}")
    return (proc.stdout or b"").decode("utf-8", errors="replace").strip()


def flatten_pages(payload: Any) -> list[dict[str, Any]]:
    if payload is None:
        return []
    if isinstance(payload, list):
        if payload and isinstance(payload[0], list):
            merged: list[dict[str, Any]] = []
            for page in payload:
                if isinstance(page, list):
                    for item in page:
                        if isinstance(item, dict):
                            merged.append(item)
            return merged
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        return [payload]
    return []


def to_abs_output(path: Path) -> Path:
    return path if path.is_absolute() else REPO_ROOT / path


def load_existing_records(
    path: Path,
    *,
    key: str,
    verbose: bool,
) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        log(verbose, f"Ignoring unreadable prior output at {path}; starting fresh.")
        return []

    if not isinstance(payload, dict):
        return []
    records = payload.get(key)
    if not isinstance(records, list):
        return []

    valid_records = [record for record in records if isinstance(record, dict)]
    if valid_records:
        log(verbose, f"Resuming with {len(valid_records)} existing {key} records from {path}.")
    return valid_records


def user_summary(user_obj: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(user_obj, dict):
        return None
    return {
        "login": user_obj.get("login"),
        "id": user_obj.get("id"),
        "type": user_obj.get("type"),
        "site_admin": user_obj.get("site_admin"),
        "html_url": user_obj.get("html_url"),
    }


def collect_user_logins(items: Iterable[dict[str, Any]]) -> list[str]:
    users: set[str] = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        user_obj = item.get("user")
        if isinstance(user_obj, dict):
            login = user_obj.get("login")
            if isinstance(login, str) and login:
                users.add(login)
    return sorted(users)


def merge_pr_thread(
    issue_comments: list[dict[str, Any]],
    reviews: list[dict[str, Any]],
    review_comments: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []

    for comment in issue_comments:
        merged.append(
            {
                "event_type": "issue_comment",
                "created_at": comment.get("created_at"),
                "updated_at": comment.get("updated_at"),
                "user": user_summary(comment.get("user")),
                "raw": comment,
            }
        )

    for review in reviews:
        merged.append(
            {
                "event_type": "review",
                "created_at": review.get("submitted_at") or review.get("created_at"),
                "updated_at": review.get("submitted_at") or review.get("updated_at"),
                "user": user_summary(review.get("user")),
                "raw": review,
            }
        )

    for review_comment in review_comments:
        merged.append(
            {
                "event_type": "review_comment",
                "created_at": review_comment.get("created_at"),
                "updated_at": review_comment.get("updated_at"),
                "user": user_summary(review_comment.get("user")),
                "raw": review_comment,
            }
        )

    merged.sort(key=lambda item: (item.get("created_at") or "", item.get("event_type") or ""))
    return merged


def fetch_repo_metadata(repo: str, verbose: bool) -> dict[str, Any]:
    endpoint = f"repos/{repo}"
    payload = run_gh_json(endpoint, paginate=False, verbose=verbose)
    if not isinstance(payload, dict):
        raise GhError(f"Unexpected repository metadata response for '{repo}'.")
    return payload


def fetch_issues(repo: str, state: str, verbose: bool) -> list[dict[str, Any]]:
    endpoint = f"repos/{repo}/issues?state={state}&per_page=100&direction=asc"
    all_items = flatten_pages(run_gh_json(endpoint, paginate=True, verbose=verbose))
    issues = [item for item in all_items if "pull_request" not in item]
    issues.sort(key=lambda item: int(item.get("number") or 0))
    return issues


def fetch_issue_comments(repo: str, issue_number: int, verbose: bool) -> list[dict[str, Any]]:
    endpoint = f"repos/{repo}/issues/{issue_number}/comments?per_page=100"
    comments = flatten_pages(run_gh_json(endpoint, paginate=True, verbose=verbose))
    comments.sort(key=lambda item: item.get("created_at") or "")
    return comments


def fetch_pr_numbers(repo: str, state: str, verbose: bool) -> list[int]:
    endpoint = f"repos/{repo}/pulls?state={state}&per_page=100&direction=asc"
    pr_items = flatten_pages(run_gh_json(endpoint, paginate=True, verbose=verbose))
    numbers: list[int] = []
    for item in pr_items:
        number = item.get("number")
        if isinstance(number, int):
            numbers.append(number)
    return sorted(numbers)


def fetch_pr_detail(repo: str, pr_number: int, verbose: bool) -> dict[str, Any]:
    endpoint = f"repos/{repo}/pulls/{pr_number}"
    payload = run_gh_json(endpoint, paginate=False, verbose=verbose)
    if not isinstance(payload, dict):
        raise GhError(f"Unexpected PR detail for #{pr_number} in {repo}.")
    return payload


def fetch_pr_reviews(repo: str, pr_number: int, verbose: bool) -> list[dict[str, Any]]:
    endpoint = f"repos/{repo}/pulls/{pr_number}/reviews?per_page=100"
    reviews = flatten_pages(run_gh_json(endpoint, paginate=True, verbose=verbose))
    reviews.sort(key=lambda item: item.get("submitted_at") or item.get("created_at") or "")
    return reviews


def fetch_pr_review_comments(repo: str, pr_number: int, verbose: bool) -> list[dict[str, Any]]:
    endpoint = f"repos/{repo}/pulls/{pr_number}/comments?per_page=100"
    review_comments = flatten_pages(run_gh_json(endpoint, paginate=True, verbose=verbose))
    review_comments.sort(key=lambda item: item.get("created_at") or "")
    return review_comments


def build_issue_record(issue: dict[str, Any], comments: list[dict[str, Any]]) -> dict[str, Any]:
    author = user_summary(issue.get("user"))
    involved_users = collect_user_logins([issue, *comments])
    return {
        "number": issue.get("number"),
        "type": "issue",
        "author": author,
        "body": issue.get("body"),
        "involved_users": involved_users,
        "comment_count": len(comments),
        "issue": issue,
        "comments": comments,
    }


def build_pr_record(
    pr: dict[str, Any],
    issue_comments: list[dict[str, Any]],
    reviews: list[dict[str, Any]],
    review_comments: list[dict[str, Any]],
) -> dict[str, Any]:
    author = user_summary(pr.get("user"))
    involved_users = sorted(
        set(
            collect_user_logins([pr])
            + collect_user_logins(issue_comments)
            + collect_user_logins(reviews)
            + collect_user_logins(review_comments)
        )
    )
    merged_thread = merge_pr_thread(issue_comments, reviews, review_comments)
    return {
        "number": pr.get("number"),
        "type": "pr",
        "author": author,
        "body": pr.get("body"),
        "involved_users": involved_users,
        "comment_counts": {
            "issue_comments": len(issue_comments),
            "reviews": len(reviews),
            "review_comments": len(review_comments),
            "thread_events": len(merged_thread),
        },
        "pr": pr,
        "issue_comments": issue_comments,
        "reviews": reviews,
        "review_comments": review_comments,
        "thread": merged_thread,
    }


def write_json(
    path: Path,
    payload: dict[str, Any],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    temp_path = path.with_suffix(path.suffix + ".tmp")
    temp_path.write_text(text, encoding="utf-8")
    temp_path.replace(path)


def build_issues_output(
    common_export_metadata: dict[str, Any],
    repository: dict[str, Any],
    issue_records: list[dict[str, Any]],
    *,
    status: str,
) -> dict[str, Any]:
    return {
        "export_metadata": {
            **common_export_metadata,
            "type": "issues",
            "status": status,
            "item_count": len(issue_records),
            "comment_count": sum(item["comment_count"] for item in issue_records),
        },
        "repository": repository,
        "issues": issue_records,
    }


def build_prs_output(
    common_export_metadata: dict[str, Any],
    repository: dict[str, Any],
    pr_records: list[dict[str, Any]],
    *,
    status: str,
) -> dict[str, Any]:
    return {
        "export_metadata": {
            **common_export_metadata,
            "type": "prs",
            "status": status,
            "item_count": len(pr_records),
            "issue_comment_count": sum(
                item["comment_counts"]["issue_comments"] for item in pr_records
            ),
            "review_count": sum(item["comment_counts"]["reviews"] for item in pr_records),
            "review_comment_count": sum(
                item["comment_counts"]["review_comments"] for item in pr_records
            ),
        },
        "repository": repository,
        "prs": pr_records,
    }


def gh_version() -> str:
    try:
        return run_gh_text(["gh", "--version"]).splitlines()[0]
    except GhError:
        return "unknown"


def ensure_gh_available() -> None:
    if shutil.which("gh") is None:
        raise GhError(
            "GitHub CLI executable not found. Install gh and add it to PATH."
        )


def main() -> None:
    global GH_MAX_RETRIES
    args = parse_args()
    GH_MAX_RETRIES = max(0, args.max_retries)
    owner, repo_name = ensure_repo_format(args.repo)
    output_root = to_abs_output(args.output_root)
    repo_output_dir = output_root / owner / repo_name
    do_issues = args.type in ("issues", "both")
    do_prs = args.type in ("prs", "both")

    try:
        ensure_gh_available()
        log(args.verbose, f"Fetching repository metadata for {args.repo}...")
        repository = fetch_repo_metadata(args.repo, args.verbose)

        run_started_at = datetime.now(timezone.utc).isoformat()
        common_export_metadata = {
            "exported_at": run_started_at,
            "repo": args.repo,
            "state": args.state,
            "source": "gh api",
            "gh_version": gh_version(),
        }

        issue_records: list[dict[str, Any]] = []
        issues_path = repo_output_dir / "issues.json"
        prs_path = repo_output_dir / "prs.json"

        if do_issues:
            issue_records = load_existing_records(
                issues_path,
                key="issues",
                verbose=args.verbose,
            )
            completed_issue_numbers = {
                number
                for number in (item.get("number") for item in issue_records)
                if isinstance(number, int)
            }

            log(args.verbose, f"Fetching issues for {args.repo} (state={args.state})...")
            issues = fetch_issues(args.repo, args.state, args.verbose)
            for issue in issues:
                raw_number = issue.get("number")
                if not isinstance(raw_number, int):
                    raise GhError(f"Issue without valid number encountered: {issue.get('id')}")
                number = raw_number
                if number in completed_issue_numbers:
                    continue
                log(args.verbose, f"  Expanding issue #{number} comments...")
                comments = fetch_issue_comments(args.repo, number, args.verbose)
                issue_records.append(build_issue_record(issue, comments))
                completed_issue_numbers.add(number)
                write_json(
                    issues_path,
                    build_issues_output(
                        common_export_metadata,
                        repository,
                        issue_records,
                        status="in_progress",
                    ),
                )

        pr_records: list[dict[str, Any]] = []
        if do_prs:
            pr_records = load_existing_records(
                prs_path,
                key="prs",
                verbose=args.verbose,
            )
            completed_pr_numbers = {
                number
                for number in (item.get("number") for item in pr_records)
                if isinstance(number, int)
            }

            log(args.verbose, f"Fetching PR numbers for {args.repo} (state={args.state})...")
            pr_numbers = fetch_pr_numbers(args.repo, args.state, args.verbose)
            for pr_number in pr_numbers:
                if pr_number in completed_pr_numbers:
                    continue
                log(args.verbose, f"  Expanding PR #{pr_number}..." )
                pr_detail = fetch_pr_detail(args.repo, pr_number, args.verbose)
                issue_comments = fetch_issue_comments(args.repo, pr_number, args.verbose)
                reviews = fetch_pr_reviews(args.repo, pr_number, args.verbose)
                review_comments = fetch_pr_review_comments(args.repo, pr_number, args.verbose)
                pr_records.append(
                    build_pr_record(pr_detail, issue_comments, reviews, review_comments)
                )
                completed_pr_numbers.add(pr_number)
                write_json(
                    prs_path,
                    build_prs_output(
                        common_export_metadata,
                        repository,
                        pr_records,
                        status="in_progress",
                    ),
                )

        if do_issues:
            issues_output = build_issues_output(
                common_export_metadata,
                repository,
                issue_records,
                status="completed",
            )
            write_json(issues_path, issues_output)
            print(f"Wrote {issues_path}")

        if do_prs:
            prs_output = build_prs_output(
                common_export_metadata,
                repository,
                pr_records,
                status="completed",
            )
            write_json(prs_path, prs_output)
            print(f"Wrote {prs_path}")
    except (GhError, ValueError) as exc:
        sys.exit(f"Error: {exc}")


if __name__ == "__main__":
    main()
