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
  --master local[*] \
  --class EarthquakeAnalysis \
  target/scala-2.12/earthquake-analysis-assembly-1.0.jar \
  data/dataset-earthquakes-trimmed.csv
```

Optional: specify repartition count as second argument:
```bash
spark-submit \
  --master local[*] \
  --class EarthquakeAnalysis \
  target/scala-2.12/earthquake-analysis-assembly-1.0.jar \
  data/dataset-earthquakes-trimmed.csv 16
```

## Run on Google Cloud DataProc

### 1. Upload JAR and dataset to GCS

```bash
gsutil mb gs://YOUR_BUCKET/
gsutil cp target/scala-2.12/earthquake-analysis-assembly-1.0.jar gs://YOUR_BUCKET/
gsutil cp data/dataset-earthquakes-full.csv gs://YOUR_BUCKET/
```

### 2. Create cluster and submit job

Repeat for each worker count (2, 3, 4) to measure scalability. Always delete the cluster after each experiment to save credits.

**2 workers:**
```bash
gcloud dataproc clusters create eq-cluster-2 \
  --region=europe-west1 \
  --num-workers 2 \
  --master-boot-disk-size 240 \
  --worker-boot-disk-size 240 \
  --master-machine-type=n2-standard-4 \
  --worker-machine-type=n2-standard-4

gcloud dataproc jobs submit spark \
  --cluster=eq-cluster-2 \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv

gcloud dataproc clusters delete eq-cluster-2 --region=europe-west1
```

**3 workers:**
```bash
gcloud dataproc clusters create eq-cluster-3 \
  --region=europe-west1 \
  --num-workers 3 \
  --master-boot-disk-size 240 \
  --worker-boot-disk-size 240 \
  --master-machine-type=n2-standard-4 \
  --worker-machine-type=n2-standard-4

gcloud dataproc jobs submit spark \
  --cluster=eq-cluster-3 \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv

gcloud dataproc clusters delete eq-cluster-3 --region=europe-west1
```

**4 workers:**
```bash
gcloud dataproc clusters create eq-cluster-4 \
  --region=europe-west1 \
  --num-workers 4 \
  --master-boot-disk-size 240 \
  --worker-boot-disk-size 240 \
  --master-machine-type=n2-standard-4 \
  --worker-machine-type=n2-standard-4

gcloud dataproc jobs submit spark \
  --cluster=eq-cluster-4 \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv

gcloud dataproc clusters delete eq-cluster-4 --region=europe-west1
```

### 3. Submit job with custom repartition

Pass the repartition count as the second argument (after `--`):

```bash
gcloud dataproc jobs submit spark \
  --cluster=eq-cluster-2 \
  --region=europe-west1 \
  --jar=gs://YOUR_BUCKET/earthquake-analysis-assembly-1.0.jar \
  -- gs://YOUR_BUCKET/dataset-earthquakes-full.csv 16
```

## Output Format

The program prints the pair of locations with the highest number of co-occurrence days, followed by each co-occurrence date in ascending order:

```
((lat1, lon1), (lat2, lon2))
YYYY-MM-DD
YYYY-MM-DD
...
```

Example:
```
((37.5, 15.3), (38.1, 13.4))
2024-03-12
2024-04-01
2024-04-03
```

## Algorithm

1. Load CSV, parse longitude/latitude/date
2. Round coordinates to 1 decimal place (HALF_UP rounding)
3. Extract day (YYYY-MM-DD) from timestamp
4. Remove duplicate (day, location) pairs caused by rounding
5. Group locations by day
6. Generate all unordered pairs of different locations per day
7. Count co-occurrences per pair using reduceByKey
8. Select the pair with maximum count
9. Recover and sort the co-occurrence dates
10. Print result
