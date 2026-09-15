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

Repository objects and JSONL output are stored under `.cache/` and `output/`,
respectively, and both are ignored by Git. Use `--help` for worker and fetch
batch counts, developer/repository filters, alternate paths, refresh behavior,
and error retries. Do not run two audit processes against the same cache and
output paths concurrently.
