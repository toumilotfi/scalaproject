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
