// =============================================================================
// Fire Control System (FCS) - Build Configuration
// Akka · Scala · Apache Kafka · Réseaux de Pétri · LTL
// =============================================================================

ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "com.fcs"

lazy val root = (project in file("."))
  .settings(
    name := "fire-control-system",

    // --- Akka ---
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed"   % "2.8.8" cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-stream"         % "2.8.8" cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-slf4j"          % "2.8.8" cross CrossVersion.for3Use2_13,
    ),

    // --- Alpakka Kafka (Akka Streams Kafka) ---
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-kafka" % "5.0.0" cross CrossVersion.for3Use2_13,
    ),

    // --- Kafka Client ---
    libraryDependencies ++= Seq(
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
    ),

    // --- Logging ---
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.5.6",
    ),

    // --- JSON (pour sérialisation Kafka) ---
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"    % "0.14.9",
      "io.circe" %% "circe-generic" % "0.14.9",
      "io.circe" %% "circe-parser"  % "0.14.9",
    ),

    // --- Test ---
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.8.8" % Test cross CrossVersion.for3Use2_13,
      "org.scalatest"     %% "scalatest"                % "3.2.19" % Test,
      "io.github.embeddedkafka" %% "embedded-kafka"     % "3.7.0"  % Test cross CrossVersion.for3Use2_13,
    ),

    // --- Compiler Options ---
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
    ),

    // --- Fork JVM for run ---
    run / fork := true,
    run / javaOptions ++= Seq(
      "-Xms512m",
      "-Xmx2g",
    ),
  )
