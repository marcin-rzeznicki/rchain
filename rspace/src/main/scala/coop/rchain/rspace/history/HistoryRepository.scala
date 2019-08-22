package coop.rchain.rspace.history

import cats.implicits._
import cats.effect.Sync
import cats.temp.par.Par

import coop.rchain.metrics.Metrics
import coop.rchain.rspace.{Blake2b256Hash, HistoryReader, HotStoreAction, RSpaceMetricsSource}

import org.lmdbjava.EnvFlags
import scodec.Codec

trait HistoryRepository[F[_], C, P, A, K] extends HistoryReader[F, C, P, A, K] {
  def checkpoint(actions: List[HotStoreAction]): F[HistoryRepository[F, C, P, A, K]]

  def reset(root: Blake2b256Hash): F[HistoryRepository[F, C, P, A, K]]

  def history: History[F]

  def close(): F[Unit]
}

final case class LMDBStorageConfig(
    path: String,
    mapSize: Long,
    maxReaders: Int = 2048,
    maxDbs: Int = 2,
    flags: List[EnvFlags] = Nil,
    dbNamePrefix: String = "db"
)
final case class LMDBRSpaceStorageConfig(
    coldStore: StoreConfig,
    historyStore: StoreConfig,
    rootsStore: StoreConfig
)

object HistoryRepositoryInstances {

  private val RootsStoreMetricsSource: Metrics.Source =
    Metrics.Source(RSpaceMetricsSource, "roots-store")
  private val ColdStoreMetricsSource: Metrics.Source =
    Metrics.Source(RSpaceMetricsSource, "cold-store")
  private val HistoryStoreMetricsSource: Metrics.Source =
    Metrics.Source(RSpaceMetricsSource, "history-store")

  def lmdbRepository[F[_]: Sync: Par: Metrics, C, P, A, K](
      config: LMDBRSpaceStorageConfig
  )(
      implicit codecC: Codec[C],
      codecP: Codec[P],
      codecA: Codec[A],
      codecK: Codec[K]
  ): F[HistoryRepository[F, C, P, A, K]] =
    for {
      rootsLMDBStore  <- StoreInstances.lmdbStore[F](config.rootsStore, RootsStoreMetricsSource)
      rootsRepository = new RootRepository[F](RootsStoreInstances.rootsStore[F](rootsLMDBStore))
      currentRoot     <- rootsRepository.currentRoot()
      coldLMDBStore   <- StoreInstances.lmdbStore[F](config.coldStore, ColdStoreMetricsSource)
      coldStore       = ColdStoreInstances.coldStore[F](coldLMDBStore)
      historyLMDBStore <- StoreInstances
                           .lmdbStore[F](config.historyStore, HistoryStoreMetricsSource)
      historyStore = HistoryStoreInstances.historyStore[F](historyLMDBStore)
      history      = HistoryInstances.merging(currentRoot, historyStore)
    } yield HistoryRepositoryImpl[F, C, P, A, K](history, rootsRepository, coldStore)
}
