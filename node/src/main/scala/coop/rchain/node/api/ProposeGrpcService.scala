package coop.rchain.node.api

import cats.implicits._
import cats.effect.concurrent.Semaphore
import cats.effect.Concurrent

import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.engine._
import EngineCell._
import coop.rchain.casper.SafetyOracle
import coop.rchain.casper.api._
import coop.rchain.casper.protocol._
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.catscontrib.{TaskContrib, Taskable}
import coop.rchain.catscontrib.TaskContrib._
import coop.rchain.either.{Either => GrpcEither}
import coop.rchain.models.StacksafeMessage
import coop.rchain.models.either.implicits._
import coop.rchain.shared._
import coop.rchain.metrics.{Metrics, Span}

import monix.eval.Task
import monix.execution.Scheduler

private[api] object ProposeGrpcService {
  def instance[F[_]: Concurrent: Log: SafetyOracle: BlockStore: Taskable: Metrics: Span: EngineCell](
      blockApiLock: Semaphore[F],
      tracing: Boolean
  )(
      implicit worker: Scheduler
  ): ProposeServiceGrpcMonix.ProposeService =
    new ProposeServiceGrpcMonix.ProposeService {

      implicit private val logTask: Log[Task] =
        new Log[Task] {
          def isTraceEnabled(implicit ev: LogSource): Task[Boolean]     = Log[F].isTraceEnabled.toTask
          def trace(msg: => String)(implicit ev: LogSource): Task[Unit] = Log[F].trace(msg).toTask
          def debug(msg: => String)(implicit ev: LogSource): Task[Unit] = Log[F].debug(msg).toTask
          def info(msg: => String)(implicit ev: LogSource): Task[Unit]  = Log[F].info(msg).toTask
          def warn(msg: => String)(implicit ev: LogSource): Task[Unit]  = Log[F].warn(msg).toTask
          def error(msg: => String)(implicit ev: LogSource): Task[Unit] = Log[F].error(msg).toTask
          def error(msg: => String, cause: Throwable)(implicit ev: LogSource): Task[Unit] =
            Log[F].error(msg, cause).toTask
        }

      private def defer[A <: StacksafeMessage[A]](
          task: F[Either[String, A]]
      ): Task[GrpcEither] =
        Task
          .defer(task.toTask)
          .executeOn(worker)
          .attemptAndLog
          .attempt
          .map(_.fold(_.asLeft[A].toGrpcEither, _.toGrpcEither))

      override def propose(query: PrintUnmatchedSendsQuery): Task[GrpcEither] =
        defer(BlockAPI.createBlock[F](blockApiLock, Span.next, query.printUnmatchedSends))
    }
}
