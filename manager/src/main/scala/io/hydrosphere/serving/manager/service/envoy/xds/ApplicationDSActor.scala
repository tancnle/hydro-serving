package io.hydrosphere.serving.manager.service.envoy.xds

import envoy.api.v2.DiscoveryResponse
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.manager.grpc.applications.{Application, ExecutionGraph, ExecutionStage, KafkaStreaming}
import io.hydrosphere.serving.manager.model.db
import io.hydrosphere.serving.manager.model.db.ApplicationStage
import io.hydrosphere.serving.manager.service.internal_events.{ApplicationChanged, ApplicationRemoved}

import scala.collection.mutable

class ApplicationDSActor extends AbstractDSActor[Application](typeUrl = "type.googleapis.com/io.hydrosphere.serving.manager.grpc.applications.Application") {

  private val applications = mutable.Map[Long, Application]()

  override def receiveStoreChangeEvents(mes: Any): Boolean =
    mes match {
      case a: SyncApplications =>
        applications.clear()
        addOrUpdateApplications(a.applications)
        true
      case a: ApplicationChanged =>
        addOrUpdateApplications(Seq(a.application))
        true
      case a: ApplicationRemoved =>
        removeApplications(Set(a.application.id))
          .contains(true)
      case _ => false
    }

  private def addOrUpdateApplications(apps: Seq[db.Application]): Unit =
    apps.map(p => Application(
      id = p.id,
      name = p.name,
      contract = Option(p.contract),
      executionGraph = Option(ExecutionGraph(
        p.executionGraph.stages.zipWithIndex.map {
          case (stage, idx) => ExecutionStage(
            stageId = ApplicationStage.stageId(p.id, idx),
            signature = stage.signature
          )
        }
      )),
      kafkaStreaming = p.kafkaStreaming.map(k => KafkaStreaming(
        consumerId = k.consumerId.getOrElse(s"appConsumer${p.id}"),
        sourceTopic = k.sourceTopic,
        destinationTopic = k.destinationTopic,
        errorTopic = k.errorTopic.getOrElse("")
      ))
    )).foreach(a => {
      applications.put(a.id, a)
    })

  private def removeApplications(ids: Set[Long]): Set[Boolean] =
    ids.map(id => applications.remove(id).nonEmpty)

  override protected def formResources(responseObserver: StreamObserver[DiscoveryResponse]): Seq[Application] =
    applications.values.toSeq
}
