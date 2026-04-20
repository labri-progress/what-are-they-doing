#!/usr/bin/env python3
"""Analyze cumulative commit growth across all tracked developers.

The script aggregates daily commit counts from data/<handle>-YYYY-MM.json,
builds a cumulative time series, fits linear / quadratic / exponential models,
and writes:

1. A PNG figure comparing the actual cumulative curve to the fitted models.
2. A text report with fit metrics and a short growth summary.

Examples:
    python3 script/plot-cumulative-growth.py
    python3 script/plot-cumulative-growth.py --start 2025-10-01 --end 2026-02-28
    python3 script/plot-cumulative-growth.py --output figures/cumulative-growth.png
"""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from itertools import accumulate
from pathlib import Path
from datetime import date, timedelta

import matplotlib

matplotlib.use("Agg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = REPO_ROOT / "data"
DEVELOPERS_FILE = REPO_ROOT / "developers.json"
DEFAULT_OUTPUT = REPO_ROOT / "figures" / "cumulative-growth-analysis.png"
DEFAULT_SUMMARY = REPO_ROOT / "figures" / "cumulative-growth-analysis.txt"


def parse_day(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"Invalid date '{value}'. Expected YYYY-MM-DD."
        ) from exc


def resolve_output(path: Path) -> Path:
    return path if path.is_absolute() else REPO_ROOT / path


def load_tracked_handles() -> list[str]:
    developers = json.loads(DEVELOPERS_FILE.read_text())
    return [entry["handle"] for entry in developers]


def load_daily_totals(handles: list[str]) -> dict[date, int]:
    daily_totals: Counter[date] = Counter()

    for handle in handles:
        for path in sorted(DATA_DIR.glob(f"{handle}-????-??.json")):
            payload = json.loads(path.read_text())
            for day_str, day_info in payload.get("days", {}).items():
                daily_totals[date.fromisoformat(day_str)] += day_info.get("total_count", 0)

    return dict(sorted(daily_totals.items()))


def build_daily_series(
    daily_totals: dict[date, int], start: date, end: date
) -> tuple[list[date], list[int], list[int]]:
    days: list[date] = []
    counts: list[int] = []
    current = start
    while current <= end:
        days.append(current)
        counts.append(daily_totals.get(current, 0))
        current += timedelta(days=1)
    cumulative = list(accumulate(counts))
    return days, counts, cumulative


def solve_linear_system(matrix: list[list[float]], vector: list[float]) -> list[float]:
    size = len(vector)
    augmented = [row[:] + [vector[idx]] for idx, row in enumerate(matrix)]

    for pivot in range(size):
        pivot_row = max(range(pivot, size), key=lambda row: abs(augmented[row][pivot]))
        if abs(augmented[pivot_row][pivot]) < 1e-12:
            raise ValueError("Singular matrix in least-squares fit.")
        augmented[pivot], augmented[pivot_row] = augmented[pivot_row], augmented[pivot]

        pivot_value = augmented[pivot][pivot]
        for column in range(pivot, size + 1):
            augmented[pivot][column] /= pivot_value

        for row in range(size):
            if row == pivot:
                continue
            factor = augmented[row][pivot]
            for column in range(pivot, size + 1):
                augmented[row][column] -= factor * augmented[pivot][column]

    return [augmented[row][size] for row in range(size)]


def evaluate_metrics(
    actual: list[float], predicted: list[float], parameter_count: int
) -> dict[str, float]:
    n = len(actual)
    residuals = [a - p for a, p in zip(actual, predicted)]
    sse = sum(err * err for err in residuals)
    rmse = math.sqrt(sse / n) if n else 0.0
    mean_actual = sum(actual) / n if n else 0.0
    sst = sum((value - mean_actual) ** 2 for value in actual)
    if sst == 0:
        r_squared = 1.0 if sse == 0 else 0.0
    else:
        r_squared = 1.0 - (sse / sst)
    variance = max(sse / n, 1e-12) if n else 1e-12
    aic = n * math.log(variance) + 2 * parameter_count if n else 0.0
    return {
        "sse": sse,
        "rmse": rmse,
        "r_squared": r_squared,
        "aic": aic,
    }


def signed_term(value: float, suffix: str) -> str:
    sign = "+" if value >= 0 else "-"
    return f"{sign} {abs(value):.2f}{suffix}"


def signed_term_precise(value: float, suffix: str, digits: int) -> str:
    sign = "+" if value >= 0 else "-"
    return f"{sign} {abs(value):.{digits}f}{suffix}"


def fit_linear(xs: list[float], ys: list[float]) -> dict:
    n = len(xs)
    sum_x = sum(xs)
    sum_y = sum(ys)
    sum_xx = sum(x * x for x in xs)
    sum_xy = sum(x * y for x, y in zip(xs, ys))
    slope = (n * sum_xy - sum_x * sum_y) / (n * sum_xx - sum_x * sum_x)
    intercept = (sum_y - slope * sum_x) / n

    def predict(values: list[float]) -> list[float]:
        return [intercept + slope * value for value in values]

    predicted = predict(xs)
    metrics = evaluate_metrics(ys, predicted, parameter_count=2)
    return {
        "name": "linear",
        "equation": f"y = {intercept:.2f} {signed_term(slope, 'x')}",
        "predict": predict,
        **metrics,
    }


def fit_quadratic(xs: list[float], ys: list[float]) -> dict:
    n = len(xs)
    sum_x = sum(xs)
    sum_x2 = sum(x ** 2 for x in xs)
    sum_x3 = sum(x ** 3 for x in xs)
    sum_x4 = sum(x ** 4 for x in xs)
    sum_y = sum(ys)
    sum_xy = sum(x * y for x, y in zip(xs, ys))
    sum_x2y = sum((x ** 2) * y for x, y in zip(xs, ys))

    a, b, c = solve_linear_system(
        [
            [n, sum_x, sum_x2],
            [sum_x, sum_x2, sum_x3],
            [sum_x2, sum_x3, sum_x4],
        ],
        [sum_y, sum_xy, sum_x2y],
    )

    def predict(values: list[float]) -> list[float]:
        return [a + b * value + c * value * value for value in values]

    predicted = predict(xs)
    metrics = evaluate_metrics(ys, predicted, parameter_count=3)
    return {
        "name": "quadratic",
        "equation": (
            f"y = {a:.2f} {signed_term(b, 'x')} {signed_term_precise(c, 'x^2', 4)}"
        ),
        "predict": predict,
        **metrics,
    }


def fit_exponential(xs: list[float], ys: list[float]) -> dict | None:
    positive = [(x, y) for x, y in zip(xs, ys) if y > 0]
    if len(positive) < 2:
        return None

    x_vals = [x for x, _ in positive]
    log_y_vals = [math.log(y) for _, y in positive]
    n = len(positive)
    sum_x = sum(x_vals)
    sum_log_y = sum(log_y_vals)
    sum_xx = sum(x * x for x in x_vals)
    sum_xlogy = sum(x * y for x, y in zip(x_vals, log_y_vals))
    slope = (n * sum_xlogy - sum_x * sum_log_y) / (n * sum_xx - sum_x * sum_x)
    intercept = (sum_log_y - slope * sum_x) / n
    scale = math.exp(intercept)

    def predict(values: list[float]) -> list[float]:
        return [scale * math.exp(slope * value) for value in values]

    predicted = predict(xs)
    metrics = evaluate_metrics(ys, predicted, parameter_count=2)
    return {
        "name": "exponential",
        "equation": f"y = {scale:.2f} * e^({slope:.4f}x)",
        "predict": predict,
        "doubling_days": math.log(2) / slope if slope > 0 else math.inf,
        **metrics,
    }


def make_fit_table(models: list[dict], best_model: dict) -> str:
    lines = [
        "Model comparison (x = days since period start)",
        "",
        f"{'Model':<12} {'R^2':>8} {'RMSE':>12} {'AIC':>12}  Equation",
        f"{'-'*12} {'-'*8} {'-'*12} {'-'*12}  {'-'*28}",
    ]
    for model in sorted(models, key=lambda item: item["aic"]):
        marker = "*" if model["name"] == best_model["name"] else " "
        lines.append(
            f"{marker}{model['name']:<11} {model['r_squared']:>8.4f} "
            f"{model['rmse']:>12.2f} {model['aic']:>12.2f}  {model['equation']}"
        )
    return "\n".join(lines)


def summarize_growth(counts: list[int], start: date, end: date, total_commits: int, best_model: dict) -> str:
    window = min(28, len(counts) // 3) if len(counts) >= 3 else len(counts)
    early_avg = sum(counts[:window]) / window if window else 0.0
    late_avg = sum(counts[-window:]) / window if window else 0.0
    rise_factor = (late_avg / early_avg) if early_avg > 0 else math.inf
    peak_daily = max(counts) if counts else 0

    lines = [
        f"Range: {start.isoformat()} to {end.isoformat()} ({len(counts)} days)",
        f"Total cumulative commits: {total_commits}",
        f"Average daily commits, first {window} days: {early_avg:.2f}",
        f"Average daily commits, last {window} days:  {late_avg:.2f}",
        (
            f"Rise factor (late / early): {rise_factor:.2f}x"
            if math.isfinite(rise_factor)
            else "Rise factor (late / early): inf (early window is zero)"
        ),
        f"Peak daily total: {peak_daily}",
        f"Best fit by AIC: {best_model['name']}",
    ]
    if best_model["name"] == "exponential":
        doubling_days = best_model.get("doubling_days", math.inf)
        if math.isfinite(doubling_days):
            lines.append(f"Exponential doubling time: {doubling_days:.1f} days")
    elif best_model["name"] == "quadratic":
        lines.append("Interpretation: daily commit velocity is increasing over time.")
    else:
        lines.append("Interpretation: cumulative growth is closest to a constant slope.")
    return "\n".join(lines)


def plot_growth(
    days: list[date],
    cumulative: list[int],
    models: list[dict],
    best_model: dict,
    output_path: Path,
    summary_text: str,
    table_text: str,
) -> None:
    figure = plt.figure(figsize=(14, 11))
    grid = figure.add_gridspec(3, 1, height_ratios=[3.2, 2.6, 2.2])
    ax_main = figure.add_subplot(grid[0])
    ax_log = figure.add_subplot(grid[1], sharex=ax_main)
    ax_text = figure.add_subplot(grid[2])
    ax_text.axis("off")

    palette = {
        "linear": "#d95f02",
        "quadratic": "#1b9e77",
        "exponential": "#7570b3",
    }

    ax_main.plot(days, cumulative, color="#111111", linewidth=2.8, label="actual cumulative")
    ax_log.plot(days, cumulative, color="#111111", linewidth=2.4, label="actual cumulative")

    x_values = list(range(len(days)))
    for model in models:
        predicted = model["predict"](x_values)
        label = f"{model['name']} (R^2={model['r_squared']:.4f})"
        style = "-" if model["name"] == best_model["name"] else "--"
        width = 2.4 if model["name"] == best_model["name"] else 1.8
        ax_main.plot(days, predicted, style, color=palette[model["name"]], linewidth=width, label=label)
        ax_log.plot(days, predicted, style, color=palette[model["name"]], linewidth=width, label=label)

    ax_main.set_title("Cumulative Commit Growth Across All Tracked Developers", fontweight="bold")
    ax_main.set_ylabel("Cumulative commits")
    ax_main.grid(alpha=0.25)
    ax_main.legend(loc="upper left")

    ax_log.set_title("Same curve on a log scale", fontsize=11)
    ax_log.set_ylabel("Cumulative commits (log)")
    ax_log.set_yscale("log")
    ax_log.grid(alpha=0.25, which="both")
    ax_log.set_xlabel("Date")
    ax_log.xaxis.set_major_locator(mdates.MonthLocator())
    ax_log.xaxis.set_major_formatter(mdates.DateFormatter("%b %Y"))
    plt.setp(ax_log.get_xticklabels(), rotation=45, ha="right")

    ax_text.text(
        0.0,
        1.0,
        f"{summary_text}\n\n{table_text}",
        va="top",
        ha="left",
        family="monospace",
        fontsize=10,
    )

    figure.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close(figure)


def write_summary(path: Path, summary_text: str, table_text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"{summary_text}\n\n{table_text}\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fit growth models to cumulative commits across all tracked developers."
    )
    parser.add_argument(
        "--start",
        type=parse_day,
        help="First day to include (YYYY-MM-DD). Defaults to the earliest local data point.",
    )
    parser.add_argument(
        "--end",
        type=parse_day,
        help="Last day to include (YYYY-MM-DD). Defaults to the latest local data point.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"PNG output path (default: {DEFAULT_OUTPUT.relative_to(REPO_ROOT)}).",
    )
    parser.add_argument(
        "--summary-output",
        type=Path,
        default=DEFAULT_SUMMARY,
        help=f"Text summary path (default: {DEFAULT_SUMMARY.relative_to(REPO_ROOT)}).",
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    handles = load_tracked_handles()
    daily_totals = load_daily_totals(handles)
    if not daily_totals:
        raise SystemExit("No commit snapshots found under data/.")

    data_start = min(daily_totals)
    data_end = max(daily_totals)
    start = max(args.start or data_start, data_start)
    end = min(args.end or data_end, data_end)

    if start > end:
        raise SystemExit("--start must be on or before --end.")
    if len({start, end}) == 1 and start not in daily_totals:
        raise SystemExit("The selected range contains no data.")

    days, counts, cumulative = build_daily_series(daily_totals, start, end)
    if len(days) < 3:
        raise SystemExit("At least three days are required to fit growth models.")

    x_values = [float(idx) for idx in range(len(days))]
    y_values = [float(value) for value in cumulative]
    models = [fit_linear(x_values, y_values), fit_quadratic(x_values, y_values)]
    exponential = fit_exponential(x_values, y_values)
    if exponential is not None:
        models.append(exponential)

    best_model = min(models, key=lambda model: model["aic"])
    summary_text = summarize_growth(counts, start, end, cumulative[-1], best_model)
    table_text = make_fit_table(models, best_model)

    output_path = resolve_output(args.output)
    summary_path = resolve_output(args.summary_output)
    plot_growth(days, cumulative, models, best_model, output_path, summary_text, table_text)
    write_summary(summary_path, summary_text, table_text)

    print(summary_text)
    print()
    print(table_text)
    print()
    print(f"Figure saved to {output_path}")
    print(f"Summary saved to {summary_path}")


if __name__ == "__main__":
    main()
