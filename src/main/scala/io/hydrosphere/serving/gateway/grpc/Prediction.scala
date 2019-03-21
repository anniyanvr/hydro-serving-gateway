package io.hydrosphere.serving.gateway.grpc

import java.util.concurrent.atomic.AtomicReference

import cats.MonadError
import cats.effect._
import cats.instances.function._
import cats.syntax.applicativeError._
import cats.syntax.compose._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monadError._
import io.grpc.Channel
import io.hydrosphere.serving.gateway.config.Configuration
import io.hydrosphere.serving.gateway.grpc
import io.hydrosphere.serving.gateway.service.application.{ExecutionUnit, RequestTracingInfo}
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Headers}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.TensorProto
import io.hydrosphere.serving.tensorflow.types.DataType
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.Duration
import scala.util.Try


trait Prediction[F[_]] {

  def predict(
    unit: ExecutionUnit,
    request: PredictRequest,
    tracingInfo: Option[RequestTracingInfo]
  ): F[PredictionWithMetadata]

}

object Prediction extends Logging {

  type PredictionStub = PredictionServiceGrpc.PredictionServiceStub
  type PredictFunc[F[_]] = (ExecutionUnit, PredictRequest, Option[RequestTracingInfo]) => F[PredictionWithMetadata]

  def envoyBased[F[_]: LiftIO](
    channel: Channel,
    conf: Configuration
  )(implicit F: Concurrent[F], cs: ContextShift[F]): F[Prediction[F]] = {

    val predictGrpc = PredictionServiceGrpc.stub(channel)

    val prefictF = overGrpc(conf.application.grpc.deadline, predictGrpc)

    val mkReporting = if (conf.application.shadowingOn) {
      Reporting.default[F](channel, conf)
    } else {
      F.pure(Reporting.noop[F])
    }

    mkReporting map  (create0(prefictF, _))
  }

  def create0[F[_]](exec: PredictFunc[F], reporting: Reporting[F])(
    implicit F: MonadError[F, Throwable]): Prediction[F] = {

    new Prediction[F] {

      def predict(
        eu: ExecutionUnit,
        req: PredictRequest,
        tracingInfo: Option[RequestTracingInfo]
      ): F[PredictionWithMetadata] = {

        exec(eu, req, tracingInfo)
          .attempt
          .flatMap(out => {
            out match {
              case Left(e) =>
                logger.info(s"Predict failed $eu")
                logger.info(e.getMessage)
                reporting.report(req, eu, Left(e)).as(out)
              case Right(v) =>
                logger.info(s"Predict completed $eu")
                reporting.report(req, eu, Right(v)).as(out)
            }
          })
          .rethrow
      }
    }
  }

  def overGrpc[F[_]](deadline: Duration, grpcClient: PredictionStub)(
    implicit F: LiftIO[F]): PredictFunc[F] = {

    (eu: ExecutionUnit, req: PredictRequest, tracingInfo: Option[RequestTracingInfo]) => {

      val io = IO.suspend {
        val initReq = grpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, eu.serviceName)
          .withDeadlineAfter(deadline.length, deadline.unit)

        val modelVersionHeader = new AtomicReference[String](null)
        val envoyUpstreamTime = new AtomicReference[String](null)
        val setTracingF = tracingInfo.fold(identity[PredictionStub](_))(i => setTracingHeaders(_, i))
        val setCallOptsF = setCallOptions(modelVersionHeader, envoyUpstreamTime)
        val reqBuilder = (setCallOptsF >>> setTracingF) (initReq)
        IO.fromFuture(IO(reqBuilder.predict(req)))
          .map { result =>
            val latency = Try(Option(envoyUpstreamTime.get()).map(_.toLong))
              .toOption
              .flatten
              .getOrElse(0L)

            val resultWithInternalInfo = result.addInternalInfo(
              "system.latency" -> TensorProto(
                dtype = DataType.DT_INT64,
                int64Val = Seq(latency),
                tensorShape = TensorShape.scalar.toProto
              )
            )

            PredictionWithMetadata(
              response = resultWithInternalInfo,
              modelVersionId = Option(modelVersionHeader.get()),
              latency = Option(envoyUpstreamTime.get())
            )
          }
      }
      F.liftIO(io)
    }
  }

  private def setCallOptions(modelVersionHeader: AtomicReference[String], envoyUpstreamTime: AtomicReference[String]): PredictionStub => grpc.Prediction.PredictionStub =  (req: PredictionStub) => {
    req.withOption(Headers.XServingModelVersionId.callOptionsClientResponseWrapperKey, modelVersionHeader)
      .withOption(Headers.XEnvoyUpstreamServiceTime.callOptionsClientResponseWrapperKey, envoyUpstreamTime)
  }

  private def setTracingHeaders(req: PredictionStub, tracingInfo: RequestTracingInfo ): PredictionStub = {
    import tracingInfo._
    val upd1 = req.withOption(Headers.XRequestId.callOptionsKey, xRequestId)
    val upd2 = xB3requestId.fold(upd1)(id => upd1.withOption(Headers.XB3TraceId.callOptionsKey, id))
    xB3SpanId.fold(upd2)(id => upd2.withOption(Headers.XB3ParentSpanId.callOptionsKey, id))
  }

}