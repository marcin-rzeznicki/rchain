package coop.rchain.casper

import coop.rchain.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import cats._
import cats.implicits._
import cats.effect.{Concurrent, Sync}

import com.google.protobuf.ByteString
import coop.rchain.casper.engine.Running
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.rholang._
import coop.rchain.comm.transport.TransportLayer
import coop.rchain.shared._
import cats.effect.concurrent.Semaphore

import coop.rchain.blockstorage.BlockDagStorage.DeployId
import coop.rchain.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.catscontrib.ski.kp2
import coop.rchain.metrics.Span.TraceId
import coop.rchain.metrics.{Metrics, MetricsSemaphore, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator.Validator

sealed trait DeployError
final case class ParsingError(details: String)          extends DeployError
final case object MissingSignature                      extends DeployError
final case object MissingSignatureAlgorithm             extends DeployError
final case object MissingUser                           extends DeployError
final case class UnknownSignatureAlgorithm(alg: String) extends DeployError
final case object SignatureVerificationFailed           extends DeployError

object DeployError {
  def parsingError(details: String): DeployError          = ParsingError(details)
  def missingSignature: DeployError                       = MissingSignature
  def missingSignatureAlgorithm: DeployError              = MissingSignatureAlgorithm
  def missingUser: DeployError                            = MissingUser
  def unknownSignatureAlgorithm(alg: String): DeployError = UnknownSignatureAlgorithm(alg)
  def signatureVerificationFailed: DeployError            = SignatureVerificationFailed

  implicit val showDeployError: Show[DeployError] = new Show[DeployError] {
    def show(error: DeployError): String = error match {
      case ParsingError(details)          => s"Parsing error: $details"
      case MissingSignature               => s"Missing signature"
      case MissingSignatureAlgorithm      => s"Missing signature algorithm"
      case MissingUser                    => s"Missing user"
      case UnknownSignatureAlgorithm(alg) => s"Unknown signature algorithm '$alg'"
      case SignatureVerificationFailed    => "Signature verification failed"
    }
  }
}

trait Casper[F[_], A] {
  def addBlock(
      b: BlockMessage,
      handleDoppelganger: (BlockMessage, Validator) => F[Unit]
  )(implicit traceId: TraceId): F[BlockStatus]
  def contains(hash: BlockHash): F[Boolean]
  def deploy(d: DeployData): F[Either[DeployError, DeployId]]
  def estimator(dag: BlockDagRepresentation[F])(implicit traceId: TraceId): F[A]
  def createBlock(implicit traceId: TraceId): F[CreateBlockStatus]
}

trait MultiParentCasper[F[_]] extends Casper[F, IndexedSeq[BlockHash]] {
  def blockDag: F[BlockDagRepresentation[F]]
  def fetchDependencies: F[Unit]
  // This is the weight of faults that have been accumulated so far.
  // We want the clique oracle to give us a fault tolerance that is greater than
  // this initial fault weight combined with our fault tolerance threshold t.
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float]
  def lastFinalizedBlock(implicit traceId: TraceId): F[BlockMessage]
  def getRuntimeManager: F[RuntimeManager[F]]
}

object MultiParentCasper extends MultiParentCasperInstances {

  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance
  def ignoreDoppelgangerCheck[F[_]: Applicative]: (BlockMessage, Validator) => F[Unit] =
    kp2(().pure[F])

  def forkChoiceTip[F[_]: Sync: BlockStore](
      casper: MultiParentCasper[F]
  )(implicit traceId: TraceId): F[BlockMessage] =
    for {
      dag       <- casper.blockDag
      tipHashes <- casper.estimator(dag)
      tipHash   = tipHashes.head
      tip       <- ProtoUtil.getBlock[F](tipHash)
    } yield tip
}

sealed abstract class MultiParentCasperInstances {
  implicit val MetricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "casper")

  private[this] val genesisLabel = Metrics.Source(MetricsSource, "genesis")

  def hashSetCasper[F[_]: Sync: Metrics: Concurrent: ConnectionsCell: TransportLayer: Log: Time: SafetyOracle: LastFinalizedBlockCalculator: BlockStore: RPConfAsk: BlockDagStorage: Span: Running.RequestedBlocks](
      validatorId: Option[ValidatorIdentity],
      genesis: BlockMessage,
      shardId: String,
      parentTraceId: TraceId
  )(implicit runtimeManager: RuntimeManager[F]): F[MultiParentCasper[F]] =
    Span[F].noop(genesisLabel, parentTraceId) { implicit traceId =>
      for {
        dag <- BlockDagStorage[F].getRepresentation
        maybePostGenesisStateHash <- InterpreterUtil
                                      .validateBlockCheckpoint[F](
                                        genesis,
                                        dag,
                                        runtimeManager
                                      )
        postGenesisStateHash <- maybePostGenesisStateHash match {
                                 case Left(BlockException(ex)) => Sync[F].raiseError[StateHash](ex)
                                 case Right(None) =>
                                   Sync[F].raiseError[StateHash](
                                     new Exception("Genesis tuplespace validation failed!")
                                   )
                                 case Right(Some(hash)) => hash.pure[F]
                               }
        blockProcessingLock <- MetricsSemaphore.single[F]
        casperState <- Cell.mvarCell[F, CasperState](
                        CasperState()
                      )
      } yield {
        implicit val state = casperState
        new MultiParentCasperImpl[F](
          validatorId,
          genesis,
          postGenesisStateHash,
          shardId,
          blockProcessingLock
        )
      }
    }
}
