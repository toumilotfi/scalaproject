# Implementation Plan -- Earthquake Co-occurrence Analysis (Scala + Spark)

## Goal
Find the pair of different rounded locations with the highest number of co-occurrence days, and print the pair followed by the sorted list of those dates.

## Prerequisites
- JDK 11 installed
- SBT installed (`scoop install sbt` or `choco install sbt` on Windows)
- The datasets extracted from `Datasets (1).zip` into `data/`
- Google Cloud SDK (`gcloud`) installed for Phase 2

---

## Phase 1: Local Development (Milestones M1-M6)

### Step 1: Project Scaffolding
Create the sbt project structure with all build files.

**Files to create:**
- `build.sbt` -- Scala 2.12.18, Spark 3.3.2 (`provided`), sbt-assembly config
- `project/build.properties` -- `sbt.version=1.9.7`
- `project/plugins.sbt` -- `addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.1.5")`
- `.gitignore` -- target/, data/*.csv, *.jar, .idea/
- `data/test-tiny.csv` -- 10-row hand-crafted test dataset with known expected output

**build.sbt must include:**
```scala
name := "earthquake-analysis"
version := "1.0"
scalaVersion := "2.12.18"

libraryDependencies += "org.apache.spark" %% "spark-core" % "3.3.2" % "provided"
libraryDependencies += "org.apache.spark" %% "spark-sql" % "3.3.2" % "provided"

assembly / mainClass := Some("EarthquakeAnalysis")
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}
```

### Step 2: Create Test Dataset
Create `data/test-tiny.csv` with known expected output:
```
longitude,latitude,date
15.312,37.502,2024-03-12 02:10:00.000000+00:00
13.372,38.112,2024-03-12 05:55:00.000000+00:00
15.324,37.521,2024-03-12 04:32:00.000000+00:00
15.323,37.547,2024-04-01 14:22:00.000000+00:00
13.324,38.147,2024-04-01 21:55:00.000000+00:00
15.341,37.535,2024-04-03 12:32:00.000000+00:00
13.387,38.142,2024-04-03 18:33:00.000000+00:00
11.255,43.769,2024-04-03 21:10:00.000000+00:00
15.308,37.522,2024-04-23 02:10:00.000000+00:00
13.379,38.123,2024-04-27 05:55:00.000000+00:00
```

**Expected output:**
```
((37.5, 15.3), (38.1, 13.4))
2024-03-12
2024-04-01
2024-04-03
```

### Step 3: Implement EarthquakeAnalysis.scala (M1-M5)
Single file with all logic. Algorithm:

```
1. Parse args(0) as file path
2. Create SparkSession
3. Load CSV with header=true, get .rdd
4. Map each row to (day, Location(roundedLat, roundedLon))
   - longitude = row.getString(0), latitude = row.getString(1)
   - CAREFUL: CSV has longitude FIRST, latitude SECOND
   - Round: BigDecimal(x).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble
   - Day: row.getString(2).substring(0, 10)
5. Call .distinct() to remove duplicate (day, Location) pairs
6. groupByKey() => (day -> Iterable[Location])
7. flatMap: for each day, locations.toList.combinations(2) =>
   for each combo [l1, l2], create canonical pair:
     val pair = if (l1 < l2) (l1, l2) else (l2, l1)
     emit (pair, day)
8. Cache this pairPerDay RDD
9. Count: pairPerDay.map{ case (pair, day) => (pair, 1) }.reduceByKey(_ + _)
10. Find max: .reduce((a, b) => if (a._2 > b._2) a else b)
11. Recover dates: pairPerDay.filter(_._1 == bestPair).map(_._2).collect().sorted
12. Print: bestPair formatted as ((lat1, lon1), (lat2, lon2))
    then each date on its own line
13. spark.stop()
```

**Critical details:**
- `Location` must implement `Ordered[Location]` for canonical pair ordering
- Compare by lat first, then lon
- Output format must match spec exactly: `((lat, lon), (lat, lon))`

### Step 4: Local Testing (M6)
1. Run on `test-tiny.csv` -- verify output matches expected
2. Run on `dataset-earthquakes-trimmed.csv` -- verify it completes without errors
3. Check edge cases: single-location days produce no pairs (correct)

### Step 5: Extract datasets
Unzip `Datasets (1).zip` into `data/` directory.

---

## Phase 2: Cloud Deployment (Milestone M7)

### Step 6: Build FAT JAR
```bash
sbt assembly
```
Produces `target/scala-2.12/earthquake-analysis-assembly-1.0.jar`

### Step 7: Upload to GCS
```bash
gsutil mb gs://earthquake-project-<your-id>/
gsutil cp target/scala-2.12/earthquake-analysis-assembly-1.0.jar gs://earthquake-project-<your-id>/
gsutil cp data/dataset-earthquakes-full.csv gs://earthquake-project-<your-id>/
```

### Step 8: Run on DataProc (2, 3, 4 workers)
For each N in {2, 3, 4}:
```bash
# Create cluster
gcloud dataproc clusters create eq-cluster-N \
  --region=europe-west1 \
  --num-workers N \
  --master-boot-disk-size 240 \
  --worker-boot-disk-size 240 \
  --master-machine-type=n2-standard-4 \
  --worker-machine-type=n2-standard-4

# Submit job (also try different repartition values)
gcloud dataproc jobs submit spark \
  --cluster=eq-cluster-N \
  --region=europe-west1 \
  --jar=gs://earthquake-project-<id>/earthquake-analysis-assembly-1.0.jar \
  -- gs://earthquake-project-<id>/dataset-earthquakes-full.csv

# Record runtime from job output

# Delete cluster
gcloud dataproc clusters delete eq-cluster-N --region=europe-west1
```

### Step 9: Performance Experiments
- Run with repartition values: default, 8, 16, 32, 64
- Record wall-clock time for each (workers x repartition) combination
- Add optional `args(1)` to EarthquakeAnalysis for repartition count

### Step 10: Write README.md
Include:
- Project description
- How to build: `sbt assembly`
- How to run locally: `spark-submit --class EarthquakeAnalysis target/...jar data/file.csv`
- How to run on DataProc: exact gcloud commands
- How to specify repartition: optional second argument

---

## Phase 3: Report & Submission

### Step 11: Write PDF Report (max 5 pages)
Contents:
1. Approach description (algorithm, data model, pipeline stages)
2. Implementation details (Scala + Spark, RDD API)
3. Scalability analysis (2 vs 3 vs 4 workers, chart)
4. Performance analysis (repartition experiments, chart)
5. Link to GitHub repo

### Step 12: Submit
1. Push code to public GitHub repository
2. Upload PDF report to Virtuale
3. Email nicolo.pizzo2@unibo.it and gianluigi.zavattaro@unibo.it

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Pair generation blows up on days with many locations | `combinations(2)` is O(n^2) per day -- monitor and repartition if needed |
| Wrong rounding mode | Use `BigDecimal.setScale(1, HALF_UP)` explicitly |
| (A,B) vs (B,A) counted separately | Canonical ordering via `Ordered[Location]` |
| Spark version mismatch on DataProc | Pin Spark 3.3.2 + Scala 2.12.18 in build.sbt |
| Education credits exhausted | Always delete clusters after experiments |
