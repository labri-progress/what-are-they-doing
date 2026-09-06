"""Checks for weighting, temporal pairing, and missing-data semantics."""

from pathlib import Path
import tempfile
import unittest

import numpy as np
from openpyxl import Workbook
import pandas as pd

import rq4_analysis as rq4


def observations(rows):
    frame = pd.DataFrame(rows, columns=["project", "date", "ncloc"])
    frame["date"] = pd.to_datetime(frame["date"])
    for metric in rq4.METRICS:
        if metric != "ncloc":
            frame[metric] = frame["ncloc"]
    return frame


class AggregationTests(unittest.TestCase):
    def test_daily_then_monthly_weighting(self):
        rows = [("a", "2026-01-01", 100)] * 20
        rows += [("a", "2026-01-02", 0), ("b", "2026-01-01", 10)]
        daily = rq4.daily_panel(observations(rows))
        monthly, _ = rq4.monthly_panels(daily)
        values = monthly.set_index("project")["ncloc"]
        self.assertEqual(values["a"], 50)  # days, not 21 snapshot rows
        self.assertEqual(values.median(), 30)  # each project gets one value
        self.assertEqual(daily["snapshots_in_day"].max(), 20)

    def test_pairing_does_not_bridge_missing_months(self):
        daily = observations([
            ("a", "2026-01-01", 1), ("a", "2026-03-01", 100),
            ("b", "2026-01-01", 10), ("b", "2026-02-01", 14),
        ])
        _, paired = rq4.monthly_panels(daily)
        self.assertEqual(paired["project"].tolist(), ["b"])
        self.assertEqual(paired["ncloc_delta"].iloc[0], 4)

    def test_single_observation_has_no_trend(self):
        endpoints = rq4.endpoint_table(observations([("a", "2026-01-01", 10)]))
        self.assertTrue(endpoints["delta"].isna().all())
        self.assertTrue(endpoints["time_rank_rho"].isna().all())

    def test_missing_endpoint_is_not_zero(self):
        daily = observations([
            ("a", "2026-01-01", np.nan), ("a", "2026-01-02", 5),
            ("a", "2026-01-03", 10),
        ])
        endpoint = rq4.endpoint_table(daily).query("metric == 'ncloc'").iloc[0]
        self.assertEqual(endpoint["first_date"], pd.Timestamp("2026-01-02"))
        self.assertEqual(endpoint["delta"], 5)

    def test_zero_denominator_remains_missing(self):
        names = ["ncloc", "complexity", "cognitive_complexity", "code_smells", "violations",
                 "sqale_index", "bugs", "vulnerabilities", "source_complexity", "source_ncloc",
                 "test_path_ncloc", "file_ncloc", "git_churn_lines", "git_churn_commits"]
        frame = pd.DataFrame({name: [0, 10] for name in names})
        result = rq4.ratios(frame)
        for metric in ["complexity_per_kloc", "source_complexity_per_kloc", "test_path_ncloc_share", "churn_lines_per_history_commit"]:
            self.assertTrue(pd.isna(result.loc[0, metric]))

    def test_latest_day_tie_does_not_choose_arbitrary_sha(self):
        snapshots = observations([("a", "2026-01-01", 10), ("a", "2026-01-01", 20)])
        manifest = pd.DataFrame(columns=["snapshot_id"])
        details = rq4.latest_details(manifest, snapshots)
        self.assertTrue(details["selected"].empty)
        self.assertEqual(details["ambiguous_latest"]["a"], 2)

    def test_workbook_only_failure_is_discovered(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "csv").mkdir()
            project = root / "owner-project"
            project.mkdir()
            (project / "owner-project_2026-01-01_abcdef0_analyse.xlsx").touch()
            manifest, _ = rq4.discover(root)
            self.assertEqual(manifest["project"].tolist(), ["owner-project"])
            self.assertTrue(manifest["zero_byte_workbook"].iloc[0])
            self.assertFalse(manifest["has_summary"].iloc[0])

    def test_xlsx_only_snapshot_is_loaded_without_sidecar_writes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root / "owner-project"
            project.mkdir()
            workbook_path = project / "owner-project_2026-01-01_abcdef0_analyse.xlsx"
            workbook = Workbook()
            workbook.remove(workbook.active)
            for kind, sheet_name in rq4.SHEETS.items():
                sheet = workbook.create_sheet(sheet_name)
                sheet.append(rq4.HEADERS[kind])
            summary = workbook[rq4.SHEETS["summary"]]
            metrics = {
                "last_commit_date": 1767225600000,
                "ncloc": 10,
                "functions": 1,
                "complexity": 2,
                "cognitive_complexity": 3,
                "code_smells": 1,
                "violations": 1,
                "blocker_violations": 0,
                "critical_violations": 1,
                "sqale_index": 5,
                "bugs": 0,
                "vulnerabilities": 0,
                "security_hotspots": 0,
                "git_churn_commits": 2,
                "git_churn_lines": 20,
            }
            for metric, value in metrics.items():
                summary.append((metric, value))
            workbook[rq4.SHEETS["files"]].append(
                ("src/main.py", 10, 2, 3, 1, 0, 0, 0, 10, 5, 1, 2, 2, 1, 1, 0, 0)
            )
            workbook[rq4.SHEETS["functions"]].append(("src/main.py", "main", 1, 10, 10))
            workbook[rq4.SHEETS["issues"]].append(
                ("i1", "src/main.py", "S1", "python", "CRITICAL", "OPEN", 1,
                 "example", None, None, None)
            )
            workbook.save(workbook_path)
            workbook.close()

            manifest, schema = rq4.discover(root, settle_seconds=0)
            self.assertEqual(manifest["source_format"].tolist(), ["xlsx"])
            self.assertTrue(manifest["complete_tables"].iloc[0])
            self.assertEqual(set(schema["kind"]), set(rq4.SHEETS))

            with self.assertRaisesRegex(ValueError, "outside read-only report tree"):
                rq4.load_snapshots(manifest, project / "forbidden-cache", refresh=True)
            cache = root / "analysis-cache"
            snapshots = rq4.load_snapshots(manifest, cache, refresh=True)
            self.assertEqual(snapshots["ncloc"].iloc[0], 10)
            self.assertEqual(snapshots["detailed_issue_rows"].iloc[0], 1)
            self.assertEqual(snapshots["issue_count_matches_summary"].iloc[0], 1)
            self.assertEqual(snapshots["severe_violations_per_kloc"].iloc[0], 100)
            self.assertEqual(list(project.glob("*")), [workbook_path])


if __name__ == "__main__":
    unittest.main()
