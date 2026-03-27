ThisBuild / version      := "0.1.0-SNAPSHOT"
// Scala 3.3.x émet un warning JVM "sun.misc.Unsafe::objectFieldOffset" sur JDK 21+.
// C'est un bug connu de scala.runtime.LazyVals$ corrigé en Scala 3.4+.
// On reste en 3.3.4 car Akka 2.8 requiert CrossVersion.for3Use2_13, incompatible avec 3.4+.
ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "com.fcs"

lazy val root = (project in file("."))
  .settings(
    name := "fire-control-system",

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.8" cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-stream"      % "2.8.8" cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-slf4j"       % "2.8.8" cross CrossVersion.for3Use2_13,
    ),

    libraryDependencies ++= Seq(
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
    ),

    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6",
    ),

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"    % "0.14.9",
      "io.circe" %% "circe-generic" % "0.14.9",
      "io.circe" %% "circe-parser"  % "0.14.9",
    ),

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.8.8"  % Test cross CrossVersion.for3Use2_13,
      "org.scalatest"     %% "scalatest"                % "3.2.19" % Test,
    ),

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
    ),

    run / fork := true,
    run / connectInput := true,
    run / javaOptions ++= Seq(
      "-Xms512m",
      "-Xmx2g",
    ),

    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-Xms256m",
      "-Xmx1g",
    ),
    Test / testOptions += Tests.Argument("-oD"),
    Test / parallelExecution := true,
  )
