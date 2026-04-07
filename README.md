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
