package com.senderapp.sources.queue

import java.util

import akka.actor.{ Actor, ActorLogging }
import akka.stream.scaladsl.Source
import com.senderapp.Global
import com.senderapp.model.{ Events, Message, JsonMessageData }
import com.softwaremill.react.kafka.{ ConsumerProperties, PublisherWithCommitSink, ReactiveKafka }
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Deserializer
import spray.json.{ JsValue, JsonParser }

import scala.concurrent.duration._
import scala.language.postfixOps

class KafkaReaderActor extends Actor with ActorLogging {

  import Global._

  val kafka = new ReactiveKafka()
  var consumerWithOffsetSink: Option[PublisherWithCommitSink[Array[Byte], JsValue]] = None
  var meta: Map[String, String] = _
  var consumerProps: Option[ConsumerProperties[Array[Byte], JsValue]] = None
  val processor = context.actorSelection("/user/messages-router")

  override def receive: Receive = {
    case Events.Configure(name, newConfig) =>
      meta = Map("source" -> name)

      val brokers = newConfig.getString("brokerList")
      val topic = newConfig.getString("topicName")
      val group = newConfig.getString("groupId")

      stopConsumer
      initReader(brokers, topic, group)
    case e =>
      log.error(s"Unknown event: $e")
  }

  def initReader(brokers: String, topic: String, group: String) {
    log.info(s"Starting the kafka reader on $brokers, topic: $topic, group: $group")

    consumerProps = Some(ConsumerProperties(brokers, topic, group, valueDeserializer = new MessageDeserializer())
      .commitInterval(1200 milliseconds))

    consumerWithOffsetSink = consumerProps map { kafka.consumeWithOffsetSink(_) }

    Source.fromPublisher(consumerWithOffsetSink.get.publisher)
      .map(processMessage)
      .to(consumerWithOffsetSink.get.offsetCommitSink).run()
  }

  def processMessage(msg: ConsumerRecord[Array[Byte], JsValue]) = {
    val completeMeta = if (msg.key() != null) {
      meta ++ Map("topic" -> msg.topic(), "key" -> new String(msg.key()))
    } else {
      meta + ("topic" -> msg.topic())
    }

    val jsMsg = Message("trash", completeMeta, JsonMessageData(msg.value()))
    processor ! jsMsg
    msg
  }

  private def stopConsumer {
    try {
      consumerWithOffsetSink.foreach { sink =>
        log.info(s"Stopping kafka consumer")
        sink.cancel()
      }
    } catch {
      case re: RuntimeException =>
        log.warning("Error stopping offset sink:", re)
    }
  }

  override def postStop() {
    stopConsumer
    super.postStop()
  }
}

class MessageDeserializer extends Deserializer[JsValue] {
  override def configure(configs: util.Map[String, _], isKey: Boolean) {}

  override def close() {}

  override def deserialize(topic: String, data: Array[Byte]) = JsonParser(data)
}