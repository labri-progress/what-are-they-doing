# Git statistics notebook

This directory contains a self-contained notebook for computing Git churn and
file scattering for all nine developers in `developers.json`, using every
monthly snapshot from November 2025 through April 2026. September and October
are excluded because too few projects are represented during those months.

From this directory, install the environment and start Jupyter with:

```bash
uv sync
uv run jupyter lab git-statistics.ipynb
```

To execute the notebook non-interactively:

```bash
uv run jupyter nbconvert --to notebook --execute \
  --inplace git-statistics.ipynb
```

The notebook defaults to one observation per developer/SHA and includes merge
commits. Its configuration cell exposes switches for both choices.

## Independent statistics from Git

`calculate-from-git.py` recomputes additions, deletions, gross churn, and files
touched from repository objects instead of GitHub's commit-detail JSON. It uses
the November--April window by default and checkpoints every result to JSONL.

Start with the 300-file REST-response cases (223 observations in 57 repositories):

```bash
uv run python calculate-from-git.py --dry-run
uv run python calculate-from-git.py
```

Run the complete independent audit with:

```bash
uv run python calculate-from-git.py --scope all --jobs 8
```

The default `targeted` mode initializes a bare repository and batch-fetches only
the observed SHAs and their parents with depth two. It does not download every
branch, tag, or historical commit. Repositories are processed concurrently;
four workers are used by default. Existing partial or full mirrors remain
usable, so changing modes does not invalidate completed JSONL results.

Two result files are written to `output/`. `git-diff-statistics.jsonl` holds one
row per commit with its totals. `git-file-changes.csv.gz` holds one row per
changed file, with the developer, repository, SHA, day, path, previous path for
renames, and per-file additions and deletions; binary files carry empty counts
and `binary=1`. Extension and top-level directory are derived from `path` by the
consumer rather than stored. Both come from the same `git diff-tree --numstat`
output, so the file-level records cost no additional Git work; pass `--no-files`
to skip them, or `--files-output` to write them elsewhere.

A commit is considered done only once its file records exist, so rows written
before this output was introduced are recomputed on the next run. An interrupted
run can leave a commit whose file rows were written but whose per-commit row was
not; that commit is recomputed and its file rows are then appended twice, so
consumers should drop duplicates on developer, SHA, and path. The repository
objects they need are already cached, so that pass performs no network fetch.

Repository objects and both output files are stored under `.cache/` and
`output/`, respectively, and are ignored by Git. Use `--help` for worker and fetch
batch counts, developer/repository filters, alternate paths, refresh behavior,
and error retries. Do not run two audit processes against the same cache and
output paths concurrently.
