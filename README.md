# Earthquake Co-occurrence Analysis

This is my project for the *Scalable and Cloud Programming* course (Università di Bologna, AY 2025/26).

The program reads a CSV of earthquake events and finds the pair of locations (rounded to one decimal degree) that have the largest number of days where both recorded an earthquake. It also prints the list of those days.

It is written in Scala on top of Apache Spark and is meant to run on Google Cloud DataProc on the full dataset, but it also runs locally for development.

## What it actually does

Given rows like:

```
longitude,latitude,date
15.312,37.502,2024-03-12 02:10:00
13.372,38.112,2024-03-12 05:55:00
...
```

it produces:

```
((37.5, 15.3), (38.1, 13.4))
2024-03-12
2024-04-01
2024-04-03
```

A few things worth noting:

- coordinates are rounded to **1 decimal place** (HALF_UP). I do this by parsing the *string* through `BigDecimal` instead of going through `Double`, otherwise values like `13.35` end up as `13.34999...` and round the wrong way.
- before generating pairs I run `.distinct()` on `(day, location)` tuples, otherwise two raw events that round into the same cell on the same day produce a fake `(A,A)` pair and inflate the answer.
- the unordered pair `{A,B}` is stored canonically (always smaller location first) so `(A,B)` and `(B,A)` are the same key.

## Build

```bash
sbt assembly
```

This produces `target/scala-2.12/earthquake-analysis-assembly-1.0.jar` (~5.6 MB; Spark is `provided`, not bundled).

Versions: Scala 2.12.18, Spark 3.3.2, sbt 1.9.7, sbt-assembly 2.1.5.

## Run locally

For testing I used a small hand-made file (`data/test-tiny.csv`) and the trimmed dataset:

```bash
spark-submit \
  --master local[*] \
  --class EarthquakeAnalysis \
  target/scala-2.12/earthquake-analysis-assembly-1.0.jar \
  data/dataset-earthquakes-trimmed.csv
```

Optional second argument is the number of partitions to repartition the input into:

```bash
spark-submit \
  --master local[*] \
  --class EarthquakeAnalysis \
  target/scala-2.12/earthquake-analysis-assembly-1.0.jar \
  data/dataset-earthquakes-trimmed.csv 16
```

If `spark.master` is not set the program falls back to `local[*]`, so on DataProc you don't need to pass a `--master` flag (YARN is set automatically by `spark-submit`).

## Run on Google Cloud DataProc

The assignment asks to use the **full dataset** on DataProc and to try clusters of **2, 3 and 4 workers** with different `repartition` values. These are the exact commands I used (the only thing you need to change is `YOUR_BUCKET`). One-time setup first, then a small loop you repeat per experiment.

### 1. One-time setup: upload the JAR and the full dataset

```bash
gsutil mb -l europe-west1 gs://YOUR_BUCKET/
gsutil cp target/scala-2.12/earthquake-analysis-assembly-1.0.jar gs://YOUR_BUCKET/
gsutil cp data/dataset-earthquakes-full.csv gs://YOUR_BUCKET/
```

I kept the bucket in `europe-west1` because that's where my clusters were, otherwise you pay egress.

### 2. Create a cluster

The machine type and disk size below are exactly what the spec requires (`n2-standard-4`, 240 GB disks).

**With 2 workers:**

```bash
gcloud dataproc clusters create eq-cluster-2w \
  --region=europe-west1 \
  --num-workers=2 \
  --master-boot-disk-size=240 \
  --worker-boot-disk-size=240 \
  --master-machine-type=n2-standard-4 \
  --worker-machine-type=n2-standard-4 \
  --image-version=2.1-debian11 \
  --max-idle=10m
```

**With 3 workers:** same command, `--num-workers=3` and `eq-cluster-3w`.

**With 4 workers:** same command, `--num-workers=4` and `eq-cluster-4w`.

Notes from the spec / my own experience:

- `n2-standard-4` (4 vCPU, 16 GB) and 240 GB boot disks are mandatory per the project description.
- 4-worker clusters can only be created from the CLI (the web console limits you to 3).
- `--image-version=2.1-debian11` ships Spark 3.3, which matches the version I compiled against (the JAR has Spark as `provided`, so it uses whatever Spark the cluster runs).
- `--max-idle=10m` is a safety net: if I forget to delete the cluster it goes away on its own after 10 minutes of inactivity. Cheap insurance against burning education credits.

### 3. Submit the job

With **default** partitioning (no second argument):

```bash
gcloud dataproc jobs submit spark \
  --cluster=eq-cluster-2w \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv
```

With an explicit **repartition** value (second positional argument — pass 8, 16, 32, …):

```bash
gcloud dataproc jobs submit spark \
  --cluster=eq-cluster-2w \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv 16
```

The job's stdout (visible in the gcloud output and in the DataProc console) is the program's answer: the best pair on the first line, then one date per line.

I ran the four repartition values (`default`, `8`, `16`, `32`) sequentially on the same cluster before tearing it down. The runtime is in the job's `statusHistory[0].stateStartTime` → `status.stateStartTime` field; you can also read it from `gcloud dataproc jobs describe <job-id>`.

### 4. Delete the cluster

Always do this after each experiment to save credits:

```bash
gcloud dataproc clusters delete eq-cluster-2w --region=europe-west1 --quiet
```

(replace the cluster name accordingly for the 3w / 4w runs).

### Full experiment grid

To reproduce the 12 measurements in the report, the loop is: for each of the three cluster sizes (2/3/4 workers), create the cluster, submit the four jobs (default, 8, 16, 32), then delete the cluster. That's 3 cluster creations, 12 jobs, 3 cluster deletions.

## A few things I learned running it

- on the full dataset (~175 MB, 1.9 M events, 1990–2023) the dominant pair is `((38.8, -122.8), (38.8, -122.7))` — that's The Geysers geothermal field in California. They co-occur on **10,014 days**.
- without `repartition`, more workers does not help — it can actually be slower (3 workers / default was *slower* than 2 workers / default in my runs). The CSV is read into a small number of partitions and the extra cores just sit idle while the shuffle still costs.
- the sweet spot in my experiments was **4 workers with `repartition=16`** (about 8 minutes on the full dataset, vs. 17 min for 2 workers / default).
- with 16 cores and only 8 partitions (4 workers / `r=8`), the cluster is *worse* than running with default partitioning. Half the cores idle on the shuffle stage.

The full report (with the runtime table, charts and analysis) is in `report.pdf`.

## Project layout

```
.
├── build.sbt                              # sbt build, Spark deps as "provided"
├── project/                               # sbt build files (sbt-assembly plugin)
├── src/main/scala/EarthquakeAnalysis.scala
├── data/test-tiny.csv                     # 10-row test file with known output
├── results/
│   ├── results-table.md                   # all 12 DataProc runs with timings
│   ├── program-output-full.txt            # best pair + 10,014 dates
│   ├── chart_scalability.png              # runtime vs. workers
│   ├── chart_partition.png                # runtime vs. repartition
│   └── build_report.py                    # script that builds report.pdf
├── report.pdf                             # final 5-page report
└── report.tex                             # LaTeX source of the report
```

## Author

Lotfi Toumi — `lotfi.toumi@studio.unibo.it` — matricola 0001188824.
