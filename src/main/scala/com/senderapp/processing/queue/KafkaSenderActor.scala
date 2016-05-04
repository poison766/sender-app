package com.senderapp.processing.queue

import java.util

import akka.actor.{ Actor, ActorLogging, ActorRef, PoisonPill }
import akka.stream.actor.ActorSubscriberMessage
import com.senderapp.Global
import com.senderapp.model.{ Events, Message }
import com.softwaremill.react.kafka.{ ProducerMessage, ProducerProperties, ReactiveKafka }
import org.apache.kafka.common.serialization.Serializer

class KafkaSenderActor extends Actor with ActorLogging {
  import Global._

  val kafka = new ReactiveKafka()
  var producerProps: Option[ProducerProperties[Array[Byte], Message]] = None
  var producerActor: Option[ActorRef] = None

  override def receive: Receive = {
    case msg: Message =>
      log.info(s"$msg")
      producerActor.get ! ActorSubscriberMessage.OnNext(ProducerMessage(msg))

    case Events.Configure(name, newConfig) =>
      stopProducer

      if (newConfig.hasPath("brokerList") && newConfig.hasPath("topicName")) {
        val brokers = newConfig.getString("brokerList")
        val topic = newConfig.getString("topicName")
        initReader(brokers, topic)
      }

    case unknown =>
      log.error("Received unknown data: " + unknown)
  }

  def initReader(brokers: String, topic: String) {
    log.info(s"Starting the kafka writer on $brokers, topic: $topic")

    producerProps = Some(ProducerProperties(brokers, topic, new MessageSerializer()))
    producerActor = producerProps.map(kafka.producerActor(_))
  }

  private def stopProducer {
    try {
      producerActor foreach { actor =>
        log.info(s"Stopping kafka producer")
        actor ! PoisonPill
      }
    } catch {
      case re: RuntimeException =>
        log.warning("Error stopping offset sink:", re)
    }
  }

  override def postStop() {
    stopProducer
    super.postStop()
  }
}

class MessageSerializer extends Serializer[Message] {
  override def configure(configs: util.Map[String, _], isKey: Boolean) {}

  override def serialize(topic: String, msg: Message): Array[Byte] = {
    msg.body.get.getBytes("UTF-8")
  }

  override def close() {}
}