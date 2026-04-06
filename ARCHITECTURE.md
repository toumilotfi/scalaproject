# Project Architecture

## Directory Structure

```
earthquake-project/
|
|-- build.sbt                                  # SBT build config (Scala 2.12, Spark 3.3)
|-- project/
|   |-- build.properties                       # sbt.version=1.9.7
|   |-- plugins.sbt                            # sbt-assembly plugin for fat JAR
|
|-- src/
|   |-- main/
|   |   |-- scala/
|   |       |-- EarthquakeAnalysis.scala        # Main entry point (single file)
|   |
|   |-- test/
|       |-- scala/
|           |-- EarthquakeAnalysisTest.scala    # Unit tests with tiny dataset
|
|-- data/
|   |-- dataset-earthquakes-trimmed.csv         # Local testing dataset (~80MB)
|   |-- test-tiny.csv                           # Hand-crafted 10-row test dataset
|
|-- README.md                                   # DataProc execution instructions
|-- .gitignore                                  # target/, data/*.csv, *.jar
```

## Data Flow Pipeline

```
+------------------+     +-------------------+     +--------------------+
| 1. LOAD CSV      |---->| 2. NORMALIZE      |---->| 3. DEDUPLICATE     |
| spark.read.csv() |     | round(lat,lon,1d) |     | .distinct() on     |
| => RDD[Row]      |     | extract day       |     | (day, Location)    |
|                  |     | => (day, Location) |     |                    |
+------------------+     +-------------------+     +--------------------+
                                                           |
                                                           v
+------------------+     +-------------------+     +--------------------+
| 6. OUTPUT        |<----| 5. RECOVER DATES  |<----| 4. PAIR + COUNT    |
| print pair       |     | filter pairPerDay |     | groupByKey(day)    |
| print sorted     |     | for best pair     |     | generate pairs     |
| dates            |     | sort dates asc    |     | reduceByKey(_ + _) |
|                  |     |                   |     | find max           |
+------------------+     +-------------------+     +--------------------+
```

## Detailed Stage Description

### Stage 1: Load CSV
- Input: CSV file path (local or `gs://bucket/file.csv`)
- Use `spark.read.option("header", true).csv(path).rdd`
- Output: `RDD[Row]` with columns `longitude`, `latitude`, `date`

### Stage 2: Normalize
- Parse longitude (col 0) and latitude (col 1) as Double
- Round each to 1 decimal place using `BigDecimal(...).setScale(1, HALF_UP).toDouble`
- Extract day from date string: `dateStr.substring(0, 10)`
- Output: `RDD[(String, Location)]` where key = day, value = Location(lat, lon)

### Stage 3: Deduplicate
- Call `.distinct()` on `RDD[(String, Location)]`
- This ensures each (day, location) appears exactly once
- Output: `RDD[(String, Location)]` (deduplicated)

### Stage 4: Group + Pair + Count
- `groupByKey()` to get `day -> Iterable[Location]`
- For each day, generate all `combinations(2)` of locations
- Order each pair canonically: if loc1 > loc2, swap them
- `flatMap` to emit `((Location, Location), day)` for each pair per day
- **For counting**: map to `((Location, Location), 1)` then `reduceByKey(_ + _)`
- Find the pair with maximum count
- Output: best pair `(Location, Location)` and its count

### Stage 5: Recover Dates
- Keep the `pairPerDay` RDD: `((Location, Location), day)`
- Filter for the best pair only
- Collect the days, sort ascending
- Output: sorted list of date strings

### Stage 6: Output
```
((lat1, lon1), (lat2, lon2))
date1
date2
date3
...
```

## Key Design Decisions

1. **Single Scala file**: Project is small enough for one `EarthquakeAnalysis.scala`
2. **RDD API over DataFrame**: The spec code sample uses `.rdd`, and pair-generation logic is cleaner with RDD
3. **`case class Location`**: Makes pair handling type-safe and hashable
4. **Canonical pair ordering**: `if (loc1 < loc2) (loc1, loc2) else (loc2, loc1)` -- avoids (A,B)/(B,A) duplication
5. **Cache `pairPerDay`**: Used both for counting and for date recovery
6. **Command-line arg for file path**: Enables same JAR for local and DataProc runs
7. **`provided` Spark dependency**: Spark is already on the DataProc cluster; use `sbt assembly` for fat JAR without Spark bundled

## Version Matrix

| Component | Version | Reason |
|-----------|---------|--------|
| Scala | 2.12.18 | DataProc 2.1/2.2 ships Scala 2.12 |
| Spark | 3.3.2 | DataProc 2.1 default Spark version |
| SBT | 1.9.7 | Stable, works with Scala 2.12 |
| sbt-assembly | 2.1.5 | For building fat JAR |
| JDK | 11 | DataProc 2.1 uses JDK 11 |
