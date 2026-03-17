ThisBuild / version      := "0.1.0-SNAPSHOT"
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
    run / javaOptions ++= Seq(
      "-Xms512m",
      "-Xmx2g",
    ),
  )
