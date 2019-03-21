package io.hydrosphere.serving.gateway.service.application

import cats.{Applicative, Traverse}
import cats.effect.Sync
import cats.implicits._
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.GatewayError
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.grpc.Prediction
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication, StoredStage}
import io.hydrosphere.serving.model.api.TensorUtil
import io.hydrosphere.serving.model.api.json.TensorJsonLens
import io.hydrosphere.serving.model.api.tensor_builder.SignatureBuilder
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.TypedTensorFactory
import org.apache.logging.log4j.scala.Logging
import spray.json.{JsObject, JsValue}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

trait ApplicationExecutionService[F[_]] {
  def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue]

  def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue]

  def serveGrpcApplication(data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse]

  def listApps: F[Seq[StoredApplication]]
}

case class ExecutionUnit(
  serviceName: String,
  servicePath: String,
  modelVersionId: Long,
  applicationRequestId: Option[String],
  signatureName: String,
  applicationId: Long,
  stageId: String,
  applicationNamespace: Option[String],
)

class ApplicationExecutionServiceImpl[F[_]: Sync](
  applicationConfig: ApplicationConfig,
  applicationStorage: ApplicationStorage[F],
  prediction: Prediction[F],
)(implicit val ex: ExecutionContext) extends ApplicationExecutionService[F] with Logging {

  def listApps: F[Seq[StoredApplication]] = {
    applicationStorage.listAll
  }

  def getApp(name: String): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.get(name)
      app <- Sync[F].fromOption(maybeApp, GatewayError.NotFound(s"Can't find an app with name $name"))
    } yield app
  }

  def getApp(id: Long): F[StoredApplication] = {
    for {
      maybeApp <- applicationStorage.get(id)
      app <- Sync[F].fromOption(maybeApp, GatewayError.NotFound(s"Can't find an app with id $id"))
    } yield app
  }

  override def serveGrpcApplication(
    data: PredictRequest,
    tracingInfo: Option[RequestTracingInfo]
  ): F[PredictResponse] = {
    data.modelSpec match {
      case Some(modelSpec) =>
        for {
          app <- getApp(modelSpec.name)
          result <- serveApplication(app, data, tracingInfo)
        } yield result
      case None =>
        Sync[F].raiseError(GatewayError.InvalidArgument("ModelSpec is not defined"))
    }
  }

  def jsonToRequest(appName: String, inputs: JsObject, signanture: ModelSignature): Try[PredictRequest] = {
    try {
      val c = new SignatureBuilder(signanture).convert(inputs)
      c match {
        case Left(value) =>
          Failure(GatewayError.InvalidArgument(s"Validation error: $value"))
        case Right(tensors) =>
          Success(PredictRequest(
            modelSpec = Some(
              ModelSpec(
                name = appName,
                signatureName = signanture.signatureName,
                version = None
              )
            ),
            inputs = tensors.mapValues(_.toProto)
          ))
      }
    } catch {
      case x: Throwable => Failure(GatewayError.InvalidArgument(x.getMessage))
    }
  }

  override def serveJsonById(jsonServeRequest: JsonServeByIdRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeRequest.targetId)
      signature <- Sync[F].fromOption(
        app.contract.predict,
        GatewayError.NotFound(s"Tried to access invalid application. Empty contract.")
      )
      maybeRequest = jsonToRequest(app.name, jsonServeRequest.inputs, signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serveApplication(app, request, tracingInfo)
    } yield responseToJsObject(result)
  }

  override def serveJsonByName(jsonServeByNameRequest: JsonServeByNameRequest, tracingInfo: Option[RequestTracingInfo]): F[JsValue] = {
    for {
      app <- getApp(jsonServeByNameRequest.appName)
      signature <- Sync[F].fromOption(
        app.contract.predict,
        GatewayError.NotFound(s"Tried to access invalid application. Empty contract.")
      )
      maybeRequest = jsonToRequest(app.name, jsonServeByNameRequest.inputs, signature)
      request <- Sync[F].fromTry(maybeRequest)
      result <- serveApplication(app, request, tracingInfo)
    } yield responseToJsObject(result)
  }

  def verify(request: PredictRequest): Either[Map[String, GatewayError], PredictRequest] = {
    val verificationResults = request.inputs.map {
      case (name, tensor) =>
        val verifiedTensor = if (!tensor.tensorContent.isEmpty) { // tensorContent - byte field, thus skip verifications
          Right(tensor)
        } else {
          Either.fromOption(
            TensorUtil.verifyShape(tensor),
            GatewayError.InvalidTensorShape(s"$name tensor has invalid shape").asInstanceOf[GatewayError]
          )
        }
        name -> verifiedTensor
    }

    val errors = verificationResults.filter {
      case (_, t) => t.isLeft
    }.mapValues(_.left.get)

    if (errors.isEmpty) {
      val verifiedInputs = verificationResults.mapValues(_.right.get)
      Right(request.copy(inputs = verifiedInputs))
    } else {
      Left(errors)
    }
  }

  def predictConsecutiveUnits(units: Seq[ExecutionUnit], data: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    //TODO Add request id for step
    val empty = Applicative[F].pure(PredictResponse(outputs = data.inputs))
    units.foldLeft(empty) {
      case (previous, current) =>
        previous.flatMap { res =>
          val request = PredictRequest(
            modelSpec = ModelSpec(signatureName = current.servicePath).some,
            inputs = res.outputs
          )
          prediction.predict(current, request, tracingInfo).map(_.response)
        }
    }
  }

  def serveSingular(application: StoredApplication, stage: StoredStage, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    request.modelSpec match {
      case Some(_) =>
        val service = stage.services.headOption.getOrElse(throw new IllegalArgumentException(s"Can't get service from application ${application.name}"))
        val modelVersionId = service.modelVersionId
        val signature = stage.signature.getOrElse(throw new IllegalArgumentException(s"Can't get contract from application ${application.name}"))
        val unit = ExecutionUnit(
          serviceName = stage.id,
          servicePath = signature.signatureName,
          applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
          applicationId = application.id,
          signatureName = signature.signatureName,
          stageId = stage.id,
          applicationNamespace = application.namespace,
          modelVersionId = modelVersionId
        )
        predictConsecutiveUnits(Seq(unit), request, tracingInfo)
      case None => Sync[F].raiseError(GatewayError.InvalidArgument("ModelSpec in request is not specified"))
    }
  }

  def servePipeline(application: StoredApplication, stages: Seq[StoredStage], request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    val execUnits = stages.zipWithIndex.map {
      case (stage, idx) =>
        stage.signature match {
          case Some(signature) =>
            val vers = stage.services.head.modelVersionId
            Applicative[F].pure(
              ExecutionUnit(
                serviceName = stage.id,
                servicePath = signature.signatureName,
                modelVersionId = vers, // this is a safety id in case envoy doesn't return correct header
                applicationRequestId = tracingInfo.map(_.xRequestId), // TODO get real traceId
                applicationId = application.id,
                signatureName = signature.signatureName,
                stageId = stage.id,
                applicationNamespace = application.namespace
              )
            )
          case None =>
            Sync[F].raiseError[ExecutionUnit](GatewayError.NotFound(s"$stage doesn't have a signature"))
            }
        }
        for {
          units <- Traverse[List].sequence(execUnits.toList)
          res <- predictConsecutiveUnits(units, request, tracingInfo)
    } yield res
  }

  def serveApplication(application: StoredApplication, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): F[PredictResponse] = {
    for {
//      verifiedRequest <- verify(request) match {
//        case Right(correct) => Sync[F].pure(correct)
//        case Left(errors) => Sync[F].raiseError[PredictRequest](new IllegalArgumentException(errors.toString()))
//      }
      result <- application.executionGraph.stages match {
        case stage :: Nil if stage.services.lengthCompare(1) == 0 => // single stage with single service
          serveSingular(application, stage, request, tracingInfo)
        case stages => // pipeline
          servePipeline(application, stages, request, tracingInfo)
      }
    } yield result
  }

  private def responseToJsObject(rr: PredictResponse): JsObject = {
    val fields = rr.outputs.mapValues(v => TensorJsonLens.toJson(TypedTensorFactory.create(v)))
    JsObject(fields)
  }
}