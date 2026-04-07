import org.apache.spark.sql.SparkSession

case class Location(lat: Double, lon: Double) extends Ordered[Location] with Serializable {
  override def compare(that: Location): Int = {
    val c = this.lat.compare(that.lat)
    if (c != 0) c else this.lon.compare(that.lon)
  }

  override def toString: String = s"($lat, $lon)"
}

object EarthquakeAnalysis {

  /** Round to 1 decimal place from String to avoid IEEE 754 precision loss.
    * BigDecimal(double) uses the exact binary representation (e.g. 13.35 -> 13.34999...)
    * which causes HALF_UP to round DOWN at .X5 boundaries. Using BigDecimal(string) is safe. */
  def roundTo1Decimal(value: String): Double =
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

    // On DataProc, spark-submit sets spark.master automatically (yarn).
    // Locally via sbt run, no master is set, so we fall back to local[*].
    val builder = SparkSession.builder
      .appName("Earthquake Co-occurrence Analysis")
    val conf = new org.apache.spark.SparkConf()
    if (!conf.contains("spark.master")) {
      builder.master("local[*]")
    }
    val spark = builder.getOrCreate()

    // Stage 1: Load CSV
    val rawData = spark.read.option("header", value = true).csv(inputPath).rdd

    // Optionally repartition
    val data = repartitionCount match {
      case Some(n) => rawData.repartition(n)
      case None    => rawData
    }

    // Stage 2: Normalize -- map to (day, Location)
    // CSV columns: 0=longitude, 1=latitude, 2=date
    val normalized = data.map { row =>
      val lonStr = row.getString(0)
      val latStr = row.getString(1)
      val day = row.getString(2).substring(0, 10)
      val loc = Location(roundTo1Decimal(latStr), roundTo1Decimal(lonStr))
      (day, loc)
    }

    // Stage 3: Deduplicate
    val deduplicated = normalized.distinct()

    // Stage 4: Group by day, generate pairs
    val locsByDay = deduplicated.groupByKey()

    val pairPerDay = locsByDay.flatMap { case (day, locations) =>
      val locList = locations.toList
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
