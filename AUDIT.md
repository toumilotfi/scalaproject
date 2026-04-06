# Full Audit: ChatGPT Guide vs Official Project Requirements

## 1. Overview

- **Official spec**: `project-description (1).pdf` (Italian, from University of Bologna)
- **ChatGPT guide**: `scala_spark_project_start_guide.pdf` (English, 10-step roadmap)
- **Project state**: EMPTY -- no code, no build files, no git repo. Only PDFs + zipped datasets.

---

## 2. Correctness Audit of the ChatGPT Guide

### CORRECT items

| # | Guide Claim | Verdict |
|---|-------------|---------|
| 1 | Round lat/lon to 1 decimal place | CORRECT -- matches spec section 1.1 |
| 2 | Convert timestamp to day (YYYY-MM-DD) | CORRECT -- spec says 1-day window |
| 3 | Remove duplicates after rounding | CORRECT -- spec explicitly warns about this |
| 4 | Generate unordered pairs per day | CORRECT -- spec asks for co-occurrence of *different* locations |
| 5 | Use canonical ordering so (A,B) == (B,A) | CORRECT -- essential for correct counting |
| 6 | Find max co-occurrence pair + print sorted dates | CORRECT -- matches spec output format |
| 7 | Use trimmed dataset locally, full on DataProc | CORRECT -- spec section 1 says this |
| 8 | Must use distributed map-reduce on Spark/DataProc | CORRECT -- spec section 1.3 |
| 9 | Test with 2, 3, 4 worker clusters | CORRECT -- spec section 4 warning box |
| 10 | Experiment with repartition values | CORRECT -- spec section 4 warning box |
| 11 | Deliverables: public GitHub + PDF report (max 5 pages) | CORRECT -- spec section 2 |
| 12 | Recommended project structure | CORRECT -- reasonable sbt layout |

### ISSUES FOUND

| # | Issue | Severity | Details |
|---|-------|----------|---------|
| 1 | **Rounding method not specified precisely** | MEDIUM | The guide says "round to 1 decimal place" but the official spec says "arrotondamento all'intero piu vicino" (rounding to nearest integer for the first decimal). This is standard `Math.round` / `BigDecimal` rounding (half-up). The guide doesn't mention the rounding mode. For Scala: use `BigDecimal(value).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble` |
| 2 | **CSV column order wrong in guide** | LOW | Guide says fields are "longitude, latitude, date". The actual CSV header is `longitude,latitude,date` -- this is actually correct, but the guide step 4 says "Read the CSV... confirm the three fields: longitude, latitude, date" which is correct. No issue here. |
| 3 | **Output format slightly underspecified** | LOW | Guide says "print the pair and sorted dates". The official spec shows exact format: `((37.5, 15.3), (38.1, 13.4))` on line 1, then each date on its own line. Guide doesn't show this exact format. |
| 4 | **Missing: dataset path for DataProc** | MEDIUM | Guide doesn't explain that on DataProc you read from `gs://bucket/file.csv`, not a local path. The program should accept the dataset path as a command-line argument. |
| 5 | **Missing: JAR packaging details** | MEDIUM | Guide says "package for DataProc" but doesn't specify to use `sbt assembly` with a fat JAR (or `sbt package` if Spark deps are `provided`). |
| 6 | **Missing: Spark version compatibility** | MEDIUM | DataProc images ship specific Spark versions. The guide doesn't mention which Scala/Spark versions to use in `build.sbt`. DataProc 2.1+ uses Spark 3.3+ / Scala 2.12. |
| 7 | **Missing: machine type requirement** | LOW | Spec mandates `n2-standard-4` for both master and workers (only via CLI). Guide doesn't mention this. |
| 8 | **Missing: deadline** | INFO | Deadline is October 15, 2026. Guide doesn't mention it. |
| 9 | **Missing: email notification** | INFO | After submission, must email nicolo.pizzo2@unibo.it and gianluigi.zavattaro@unibo.it |

### VERDICT

The ChatGPT guide is **mostly correct** and provides a good roadmap. The algorithm description is accurate. The main gaps are:
- No precise rounding mode specification
- No concrete `build.sbt` / version pinning
- No details on JAR packaging or GCS path handling
- Missing output format specification

These are all fixed in the plan below.

---

## 3. Dataset Analysis

```
File: dataset-earthquakes-trimmed.csv (79.5 MB uncompressed)
File: dataset-earthquakes-full.csv (183.1 MB uncompressed)

Header: longitude,latitude,date
Sample row: -147.3531,64.9725,2013-01-01 00:08:44.056000+00:00

Column types:
  longitude: Double (e.g., -147.3531)
  latitude:  Double (e.g., 64.9725)
  date:      Timestamp with timezone (e.g., 2013-01-01 00:08:44.056000+00:00)
```

**Key observations:**
- Dates include microseconds and timezone offset -- must extract just the date part (first 10 chars)
- Longitude comes FIRST in CSV, latitude SECOND (important for parsing)
- Values span globally (negative longitudes = western hemisphere)
