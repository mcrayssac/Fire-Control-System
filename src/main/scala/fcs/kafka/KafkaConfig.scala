package fcs.kafka

import akka.kafka.{ConsumerSettings, ProducerSettings}
import akka.actor.typed.ActorSystem
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import com.typesafe.config.Config

// =============================================================================
// Configuration Kafka pour le FCS
// Garanties critiques : acks=all, idempotence, read_committed
// =============================================================================

object KafkaConfig:

  val DefaultBootstrapServers = "localhost:9092"
  val ConsumerGroup           = "fcs-consumer-group"

  /** Configuration producteur avec garanties critiques */
  def producerSettings(system: ActorSystem[?]): ProducerSettings[String, String] =
    ProducerSettings(system, new StringSerializer, new StringSerializer)
      .withBootstrapServers(DefaultBootstrapServers)
      .withProperty(ProducerConfig.ACKS_CONFIG, "all")
      .withProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
      .withProperty(ProducerConfig.RETRIES_CONFIG, "3")
      .withProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")

  /** Configuration consommateur avec garanties critiques */
  def consumerSettings(system: ActorSystem[?]): ConsumerSettings[String, String] =
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(DefaultBootstrapServers)
      .withGroupId(ConsumerGroup)
      .withProperty(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
