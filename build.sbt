name := "AkkaStreamsCommandPipe"

organization := "io.dronekit"

version := "1.0"

scalaVersion := "2.11.7"

scalacOptions := Seq("-unchecked", "-deprecation", "-feature")

resolvers += "Artifactory" at "https://dronekit.artifactoryonline.com/dronekit/libs-snapshot-local/"

credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

isSnapshot := true

publishTo := {
  val artifactory = "https://dronekit.artifactoryonline.com/"
  if (isSnapshot.value)
    Some("snapshots" at artifactory + s"dronekit/libs-snapshot-local;build.timestamp=${new java.util.Date().getTime}")
  else
    Some("snapshots" at artifactory + "dronekit/libs-release-local")
}

libraryDependencies ++= {
  Seq(
    "com.typesafe.akka" %% "akka-stream-experimental" % "1.0",
    "com.typesafe.akka" %% "akka-stream-testkit-experimental" % "1.0" % "test",
    "com.typesafe.akka" %% "akka-testkit" % "2.3.12" % "test",
    "org.scalatest" %% "scalatest" % "2.2.4" % "test"
  )
}