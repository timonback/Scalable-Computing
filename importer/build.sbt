name := "newsforyou-importer"
dockerRepository := Some("timonback")

version := "1.0"

lazy val `importer` = (project in file("."))
  .enablePlugins(DockerPlugin)
  .enablePlugins(JavaAppPackaging)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "2.1.0",
  "org.apache.spark" %% "spark-sql" % "2.1.0",
  "org.scalaj" %% "scalaj-http" % "2.3.0",
  "org.mongodb.spark" %% "mongo-spark-connector" % "2.0.0"
)
