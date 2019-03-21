package io.hydrosphere.serving.gateway.grpc

import java.util.concurrent.Executors

import cats.data.NonEmptyList
import cats.effect._
import cats.effect.implicits._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Functor}
import io.grpc.Channel
import io.hydrosphere.serving.gateway.config.Configuration
import io.hydrosphere.serving.gateway.grpc.PredictionWithMetadata.PredictionOrException
import io.hydrosphere.serving.gateway.grpc.reqstore.{Destination, ReqStore}
import io.hydrosphere.serving.gateway.service.application.ExecutionUnit
import io.hydrosphere.serving.grpc.AuthorityReplacerInterceptor
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.MonitoringServiceGrpc.MonitoringServiceStub
import io.hydrosphere.serving.monitoring.monitoring._
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait Reporter[F[_]] {
  def send(execInfo: ExecutionInformation): F[Unit]
}

object Reporter {

  def apply[F[_]](f: ExecutionInformation => F[Unit]): Reporter[F] =
    new Reporter[F] {
      def send(execInfo: ExecutionInformation): F[Unit] = f(execInfo)
    }

  def fromFuture[F[_]: Functor, A](f: ExecutionInformation => Future[A])(implicit F: LiftIO[F]): Reporter[F] =
    Reporter(info => F.liftIO(IO.fromFuture(IO(f(info)))).void)

}

object Reporters extends Logging {

  object Monitoring {

    def envoyBased[F[_]: Functor: LiftIO](
      channel: Channel,
      destination: String,
      deadline: Duration
    ): Reporter[F] = {
      val stub = MonitoringServiceGrpc.stub(channel)
      monitoringGrpc(deadline, destination, stub)
    }

    def monitoringGrpc[F[_] : Functor : LiftIO](
      deadline: Duration,
      destination: String,
      grpcClient: MonitoringServiceStub
    ): Reporter[F] = {
      Reporter.fromFuture(info => {
        logger.info(s"Sending data to reporter (metadata=${info.metadata})")
        grpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
          .withDeadlineAfter(deadline.length, deadline.unit)
          .analyze(info)
      })
    }

  }

}


trait Reporting[F[_]] {
  def report(request: PredictRequest, eu: ExecutionUnit, value: PredictionOrException): F[Unit]
}

object Reporting {

  type MKInfo[F[_]] = (PredictRequest, ExecutionUnit, PredictionOrException) => F[ExecutionInformation]

  def default[F[_]](channel: Channel, conf: Configuration)(
    implicit F: Concurrent[F], cs: ContextShift[F]
  ): F[Reporting[F]] = {

    val appConf = conf.application
    val deadline = appConf.grpc.deadline
    val monitoring = Reporters.Monitoring.envoyBased(channel, appConf.monitoringDestination, deadline)

    val es = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutorService(es)
    prepareMkInfo(conf) map (create0(_, NonEmptyList.of(monitoring), ec))
  }

  def create0[F[_]: Concurrent](
    mkInfo: MKInfo[F],
    reporters: NonEmptyList[Reporter[F]],
    ec: ExecutionContext
  )(implicit cs: ContextShift[F]): Reporting[F] = {
    new Reporting[F] {
      def report(
        request: PredictRequest,
        eu: ExecutionUnit,
        value: PredictionOrException
      ): F[Unit] = {
        cs.evalOn(ec) {
         mkInfo(request, eu, value).flatMap(info => {
           reporters.traverse(r => r.send(info)).attempt.start
         })
        }.void
      }
    }
  }

  private def prepareMkInfo[F[_]](conf: Configuration)(implicit F: Async[F]): F[MKInfo[F]] = {
    if (conf.application.reqstore.enabled) {
      val destination = Destination.fromHttpServiceAddr(conf.application.reqstore.address, conf.sidecar)
      ReqStore.create[F, (PredictRequest, ResponseOrError)](destination)
        .map(s => {
          (req: PredictRequest, eu: ExecutionUnit, resp: PredictionOrException) => {
            s.save(eu.serviceName, (req, responseOrError(resp)))
              .attempt
              .map(d => mkExecutionInformation(req, eu, resp, d.toOption))
          }
        })
    } else {
      val f = (req: PredictRequest, eu: ExecutionUnit, value: PredictionOrException) =>
        mkExecutionInformation(req, eu, value, None).pure
      f.pure
    }
  }


  def responseOrError(poe: PredictionOrException) = {
    poe match {
      case Left(err) => ResponseOrError.Error(ExecutionError(err.getMessage))
      case Right(v) => ResponseOrError.Response(v.response)
    }
  }

  private def mkExecutionInformation(
    request: PredictRequest,
    eu: ExecutionUnit,
    value: PredictionOrException,
    traceData: Option[TraceData]
  ): ExecutionInformation = {
    val ap: Option[ExecutionMetadata] => ExecutionInformation = ExecutionInformation.apply(Option(request), _, responseOrError(value))
    val metadata = value match {
      case Left(_) =>
        Option(ExecutionMetadata(
          applicationId = eu.applicationId,
          stageId = eu.stageId,
          modelVersionId = eu.modelVersionId,
          signatureName = eu.signatureName,
          applicationRequestId = eu.applicationRequestId.getOrElse(""),
          requestId = eu.applicationRequestId.getOrElse(""), //todo fetch from response,
          applicationNamespace = eu.applicationNamespace.getOrElse(""),
          traceData = traceData
        ))
      case Right(v) =>
        Option(ExecutionMetadata(
          applicationId = eu.applicationId,
          stageId = eu.stageId,
          modelVersionId = v.modelVersionId.flatMap(x => Try(x.toLong).toOption).getOrElse(eu.modelVersionId),
          signatureName = eu.signatureName,
          applicationRequestId = eu.applicationRequestId.getOrElse(""),
          requestId = eu.applicationRequestId.getOrElse(""), //todo fetch from response,
          applicationNamespace = eu.applicationNamespace.getOrElse(""),
          traceData = traceData
        ))
    }
    ap(metadata)
  }

  def noop[F[_]](implicit F: Applicative[F]): Reporting[F] = (_, _, _) => F.pure(())
}