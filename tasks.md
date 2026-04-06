# Tasks for Implementation

> **Instructions for the AI model**: Execute these tasks sequentially. Each task is atomic and self-contained.
> The working directory is `C:\Users\lotfi\Desktop\projectscala`.
> After completing each task, mark it done and move to the next.
> Do NOT skip tasks. Do NOT add extra features beyond what is specified.
> Read ARCHITECTURE.md and plan.md first for full context.

---

## TASK 1: Extract Datasets
**Status**: [x] DONE
**Description**: Unzip the datasets into a `data/` subdirectory.
**Actions**:
1. Create directory `data/` inside the project root
2. Unzip `Datasets (1).zip` -- extract `dataset-earthquakes-trimmed.csv` and `dataset-earthquakes-full.csv` into `data/`
3. Do NOT extract the `__MACOSX/` folder
4. Verify both CSV files exist in `data/`

**Verification**: `ls data/` shows both CSV files.

---

## TASK 2: Create build.sbt
**Status**: [x] DONE
**Description**: Create the SBT build file at the project root.
**Actions**:
1. Create file `build.sbt` with this exact content:

```scala
name := "earthquake-analysis"
version := "1.0"
scalaVersion := "2.12.18"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "3.3.2" % "provided",
  "org.apache.spark" %% "spark-sql"  % "3.3.2" % "provided"
)

assembly / mainClass := Some("EarthquakeAnalysis")
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", _*) => MergeStrategy.discard
  case _                        => MergeStrategy.first
}
```

**Verification**: File exists at `./build.sbt`.

---

## TASK 3: Create project/build.properties
**Status**: [x] DONE
**Description**: Pin the SBT version.
**Actions**:
1. Create directory `project/`
2. Create file `project/build.properties` with content:
```
sbt.version=1.9.7
```

**Verification**: File exists at `./project/build.properties`.

---

## TASK 4: Create project/plugins.sbt
**Status**: [x] DONE
**Description**: Add the sbt-assembly plugin for fat JAR creation.
**Actions**:
1. Create file `project/plugins.sbt` with content:
```scala
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.1.5")
```

**Verification**: File exists at `./project/plugins.sbt`.

---

## TASK 5: Create .gitignore
**Status**: [x] DONE
**Description**: Create a .gitignore to exclude build artifacts and large data files.
**Actions**:
1. Create file `.gitignore` with content:
```
target/
data/*.csv
*.jar
.idea/
.bsp/
project/target/
project/project/
```

**Verification**: File exists at `./.gitignore`.

---

## TASK 6: Create test dataset
**Status**: [x] DONE
**Description**: Create a tiny hand-crafted CSV for testing with a known expected output.
**Actions**:
1. Create file `data/test-tiny.csv` with this exact content (note: longitude is first column, latitude is second):

```csv
longitude,latitude,date
15.312,37.502,2024-03-12 02:10:00.000000+00:00
13.372,38.112,2024-03-12 05:55:00.000000+00:00
15.324,37.521,2024-03-12 04:32:00.000000+00:00
15.323,37.547,2024-04-01 14:22:00.000000+00:00
13.372,38.147,2024-04-01 21:55:00.000000+00:00
15.341,37.535,2024-04-03 12:32:00.000000+00:00
13.387,38.142,2024-04-03 18:33:00.000000+00:00
11.255,43.769,2024-04-03 21:10:00.000000+00:00
15.308,37.522,2024-04-23 02:10:00.000000+00:00
13.379,38.123,2024-04-27 05:55:00.000000+00:00
```

**Expected output when the program runs on this file:**
```
((37.5, 15.3), (38.1, 13.4))
2024-03-12
2024-04-01
2024-04-03
```

The rounding logic:
- 37.502 -> 37.5, 15.312 -> 15.3 => Location(37.5, 15.3)
- 38.112 -> 38.1, 13.372 -> 13.4 => Location(38.1, 13.4)
- Rows 1 and 3 collapse to the same (day=2024-03-12, Location(37.5, 15.3)) after dedup
- The pair (37.5,15.3)-(38.1,13.4) co-occurs on 3 days: 2024-03-12, 2024-04-01, 2024-04-03

**Verification**: File exists at `data/test-tiny.csv` with 11 lines (1 header + 10 data rows).

---

## TASK 7: Create EarthquakeAnalysis.scala
**Status**: [x] DONE
**Description**: Implement the main Scala/Spark application.
**Actions**:
1. Create directory `src/main/scala/`
2. Create file `src/main/scala/EarthquakeAnalysis.scala` with the following implementation:

```scala
import org.apache.spark.sql.SparkSession

case class Location(lat: Double, lon: Double) extends Ordered[Location] with Serializable {
  override def compare(that: Location): Int = {
    val c = this.lat.compare(that.lat)
    if (c != 0) c else this.lon.compare(that.lon)
  }
  override def toString: String = s"($lat, $lon)"
}

object EarthquakeAnalysis {

  def roundTo1Decimal(value: Double): Double =
    BigDecimal(value).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble

  def canonicalPair(a: Location, b: Location): (Location, Location) =
    if (a <= b) (a, b) else (b, a)

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println("Usage: EarthquakeAnalysis <csv-path> [repartition-count]")
      System.exit(1)
    }

    val inputPath = args(0)
    val repartitionCount = if (args.length >= 2) Some(args(1).toInt) else None

    val spark = SparkSession.builder
      .appName("Earthquake Co-occurrence Analysis")
      .getOrCreate()

    // Stage 1: Load CSV
    val rawData = spark.read.option("header", value = true).csv(inputPath).rdd

    // Optionally repartition
    val data = repartitionCount match {
      case Some(n) => rawData.repartition(n)
      case None    => rawData
    }

    // Stage 2: Normalize -- map to (day, Location)
    val normalized = data.map { row =>
      val lon = row.getString(0).toDouble
      val lat = row.getString(1).toDouble
      val day = row.getString(2).substring(0, 10)
      val loc = Location(roundTo1Decimal(lat), roundTo1Decimal(lon))
      (day, loc)
    }

    // Stage 3: Deduplicate
    val deduplicated = normalized.distinct()

    // Stage 4: Group by day, generate pairs
    val locsByDay = deduplicated.groupByKey()

    val pairPerDay = locsByDay.flatMap { case (day, locations) =>
      val locList = locations.toList.distinct
      locList.combinations(2).map { case List(l1, l2) =>
        (canonicalPair(l1, l2), day)
      }
    }

    // Cache: used for counting AND date recovery
    pairPerDay.cache()

    // Stage 4b: Count co-occurrences
    val pairCounts = pairPerDay
      .map { case (pair, _) => (pair, 1) }
      .reduceByKey(_ + _)

    // Find the pair with the maximum count
    val bestPairWithCount = pairCounts.reduce { (a, b) =>
      if (a._2 > b._2) a else b
    }

    val bestPair = bestPairWithCount._1

    // Stage 5: Recover dates for the best pair
    val dates = pairPerDay
      .filter(_._1 == bestPair)
      .map(_._2)
      .collect()
      .sorted

    // Stage 6: Output
    println(s"(${bestPair._1}, ${bestPair._2})")
    dates.foreach(println)

    spark.stop()
  }
}
```

**CRITICAL NOTES for the implementer:**
- `row.getString(0)` is longitude, `row.getString(1)` is latitude -- CSV column order!
- `roundTo1Decimal` uses `HALF_UP` rounding as required by the spec
- `canonicalPair` ensures (A,B) and (B,A) are the same key
- `pairPerDay.cache()` avoids recomputing it for both counting and date extraction
- The second arg `repartition-count` is optional, used for performance experiments
- Output format: `((lat, lon), (lat, lon))` then dates, each on its own line

**Verification**: File exists at `src/main/scala/EarthquakeAnalysis.scala` and compiles.

---

## TASK 8: Initialize Git Repository
**Status**: [x] DONE
**Description**: Initialize git and make the first commit.
**Actions**:
1. Run `git init` in the project root
2. Run `git add build.sbt project/ src/ data/test-tiny.csv .gitignore`
3. Do NOT add the large CSV files or the PDFs
4. Commit with message: `Initial project setup with build files and main implementation`

**Verification**: `git log` shows the commit. `git status` shows no untracked source files.

---

## TASK 9: Test Locally with Tiny Dataset
**Status**: [x] DONE
**Description**: Compile and run the application on the tiny test dataset.
**Actions**:
1. First check if `spark-submit` is available. If not, modify `build.sbt` temporarily:
   - Change `% "provided"` to `% "compile"` for both Spark dependencies
   - Run `sbt "run data/test-tiny.csv"`
   - Change back to `% "provided"` after testing
2. Alternatively if `spark-submit` is available:
   - Run `sbt package`
   - Run `spark-submit --class EarthquakeAnalysis target/scala-2.12/earthquake-analysis_2.12-1.0.jar data/test-tiny.csv`
3. Compare output to expected:
```
((37.5, 15.3), (38.1, 13.4))
2024-03-12
2024-04-01
2024-04-03
```

**If output does NOT match**: Debug by adding println statements to trace:
- What locations are produced after rounding
- What (day, location) pairs exist after dedup
- What location pairs are generated per day
- What counts are produced

**Verification**: Output matches expected exactly.

---

## TASK 10: Test with Trimmed Dataset
**Status**: [ ] BLOCKED
**Description**: Run on the full trimmed dataset to verify it works on real data.
**Actions**:
1. Ensure `data/dataset-earthquakes-trimmed.csv` exists (extracted in Task 1)
2. Run the program with the trimmed dataset as input
3. The program should complete without errors and print:
   - One line with the best pair: `((lat, lon), (lat, lon))`
   - Multiple lines of dates in ascending order
4. Record the output for reference

**Blocker encountered during local verification**: the local machine only had about 20 MB free on `C:` initially, and even after freeing generated build artifacts the trimmed-data Spark job still exhausted local disk/memory resources while shuffling in local mode. The application compiled cleanly and passed the tiny-dataset verification, but this trimmed-data run could not be completed on the current machine configuration.

**Verification**: Program completes without errors. Output looks reasonable (pair of locations + sorted date list).

---

## TASK 11: Build Fat JAR
**Status**: [ ] TODO
**Description**: Build the assembly JAR for DataProc deployment.
**Actions**:
1. Ensure `build.sbt` has Spark dependencies as `% "provided"`
2. Run `sbt assembly`
3. Verify the JAR is created at `target/scala-2.12/earthquake-analysis-assembly-1.0.jar`

**Verification**: JAR file exists and is reasonable size (should be small, < 1MB, since Spark is provided).

---

## TASK 12: Create README.md
**Status**: [ ] TODO
**Description**: Write the README with DataProc execution instructions.
**Actions**:
1. Create file `README.md` with the following content:

```markdown
# Earthquake Co-occurrence Analysis

Scala + Spark application that finds the pair of rounded earthquake locations with the highest number of co-occurrence days.

## Build

```bash
sbt assembly
```

Produces: `target/scala-2.12/earthquake-analysis-assembly-1.0.jar`

## Run Locally

```bash
spark-submit \
  --class EarthquakeAnalysis \
  target/scala-2.12/earthquake-analysis-assembly-1.0.jar \
  data/dataset-earthquakes-trimmed.csv
```

Optional: specify repartition count as second argument:
```bash
spark-submit ... data/dataset-earthquakes-trimmed.csv 16
```

## Run on Google Cloud DataProc

### 1. Upload JAR and dataset to GCS

```bash
gsutil mb gs://YOUR_BUCKET/
gsutil cp target/scala-2.12/earthquake-analysis-assembly-1.0.jar gs://YOUR_BUCKET/
gsutil cp data/dataset-earthquakes-full.csv gs://YOUR_BUCKET/
```

### 2. Create cluster

```bash
gcloud dataproc clusters create eq-cluster \
  --region=europe-west1 \
  --num-workers 2 \
  --master-boot-disk-size 240 \
  --worker-boot-disk-size 240 \
  --master-machine-type=n2-standard-4 \
  --worker-machine-type=n2-standard-4
```

### 3. Submit job

```bash
gcloud dataproc jobs submit spark \
  --cluster=eq-cluster \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv
```

With repartition:
```bash
gcloud dataproc jobs submit spark \
  --cluster=eq-cluster \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv 16
```

### 4. Delete cluster

```bash
gcloud dataproc clusters delete eq-cluster --region=europe-west1
```

## Algorithm

1. Load CSV, parse longitude/latitude/date
2. Round coordinates to 1 decimal place (HALF_UP)
3. Extract day from timestamp
4. Remove duplicate (day, location) pairs
5. Group locations by day
6. Generate all unordered pairs of different locations per day
7. Count co-occurrences per pair using reduceByKey
8. Select the pair with maximum count
9. Recover and sort the co-occurrence dates
10. Print result
```

**Verification**: File exists at `./README.md`.

---

## TASK 13: Commit All Remaining Files
**Status**: [ ] TODO
**Description**: Add README and any fixes, commit.
**Actions**:
1. `git add README.md`
2. `git add -u` (to catch any modified files)
3. Commit with message: `Add README with DataProc instructions`

**Verification**: `git log` shows two commits. Working tree is clean.

---

## TASK 14 (MANUAL -- user does this): DataProc Experiments
**Status**: [ ] TODO -- USER TASK
**Description**: This task must be done by the user on Google Cloud. It cannot be automated.
**Actions**:
1. Create a GCS bucket and upload JAR + full dataset
2. For each cluster size (2, 3, 4 workers):
   a. Create cluster with `n2-standard-4` machines
   b. Run job with default repartition -- record time
   c. Run job with repartition=8 -- record time
   d. Run job with repartition=16 -- record time
   e. Run job with repartition=32 -- record time
   f. Delete cluster immediately after
3. Record all times in a table for the report

**Output table format:**
| Workers | Repartition | Time (seconds) |
|---------|-------------|---------------|
| 2 | default | ... |
| 2 | 8 | ... |
| 2 | 16 | ... |
| ... | ... | ... |

---

## TASK 15 (MANUAL -- user does this): Write Report & Submit
**Status**: [ ] TODO -- USER TASK
**Description**: Write the PDF report and submit.
**Actions**:
1. Write PDF report (max 5 pages) covering:
   - Approach (algorithm description)
   - Implementation details
   - Scalability analysis (workers chart)
   - Performance analysis (repartition chart)
   - Link to GitHub repository
2. Push code to public GitHub repo
3. Upload report to Virtuale
4. Email professors: nicolo.pizzo2@unibo.it, gianluigi.zavattaro@unibo.it
5. Deadline: October 15, 2026
