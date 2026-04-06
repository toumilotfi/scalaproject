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
