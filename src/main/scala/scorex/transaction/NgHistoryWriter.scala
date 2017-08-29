package scorex.transaction

import java.util.concurrent.locks.ReentrantReadWriteLock

import com.wavesplatform.state2._
import kamon.Kamon
import kamon.metric.instrument.Time
import scorex.block.Block.BlockId
import scorex.block.{Block, MicroBlock}
import scorex.transaction.History.BlockchainScore
import scorex.transaction.ValidationError.{BlockAppendError, MicroBlockAppendError}
import scorex.utils.ScorexLogging

trait NgHistoryWriter extends HistoryWriter with NgHistory {
  def appendMicroBlock(microBlock: MicroBlock)(microBlockConsensusValidation: Long => Either[ValidationError, BlockDiff]): Either[ValidationError, BlockDiff]

  def baseBlock(): Option[Block]

  def bestLiquidBlock(): Option[Block]

  def forgeBlock(id: BlockId): Option[(Block, DiscardedMicroBlocks)]
}

class NgHistoryWriterImpl(inner: HistoryWriter) extends NgHistoryWriter with ScorexLogging with Instrumented {

  override def synchronizationToken: ReentrantReadWriteLock = inner.synchronizationToken

  private val baseB = Synchronized(Option.empty[Block])
  private val micros = Synchronized(List.empty[MicroBlock])

  def baseBlock(): Option[Block] = read { implicit l => baseB() }

  def lastMicroTotalSig(): Option[ByteStr] = read { implicit l =>
    micros().headOption.map(_.totalResBlockSig)
  }

  def bestLiquidBlock(): Option[Block] = read { implicit l =>
    baseB().map(base => {
      val ms = micros()
      if (ms.isEmpty) {
        base
      } else {
        base.copy(
          signerData = base.signerData.copy(signature = ms.head.totalResBlockSig),
          transactionData = base.transactionData ++ ms.map(_.transactionData).reverse.flatten)
      }
    })
  }

  override def appendBlock(block: Block)(consensusValidation: => Either[ValidationError, BlockDiff]): Either[ValidationError, (BlockDiff, DiscardedTransactions)]
  = write { implicit l => {
    lazy val logDetails = s"The referenced block(${block.reference}) ${if (inner.contains(block.reference)) "exits, it's not last persisted" else "doesn't exist"}"
    if (baseB().isEmpty) {
      inner.lastBlock match {
        case Some(lastInner) if lastInner.uniqueId != block.reference =>
          Left(BlockAppendError(s"References incorrect or non-existing block: " + logDetails, block))
        case _ => consensusValidation.map((_, Seq.empty[Transaction]))
      }
    }
    else forgeBlock(block.reference) match {
      case Some((forgedBlock, discarded)) =>
        if (forgedBlock.signatureValid) {
          if (discarded.nonEmpty) {
            microBlockForkStats.increment()
            microBlockForkHeightStats.record(discarded.size)
          }
          inner.appendBlock(forgedBlock)(consensusValidation).map(dd => (dd._1, discarded.flatMap(_.transactionData)))
        } else {
          val errorText = s"Forged block has invalid signature: base: ${baseB()}, micros: ${micros()}, requested reference: ${block.reference}"
          log.error(errorText)
          Left(BlockAppendError(s"ERROR: $errorText", block))

        }
      case None =>
        Left(BlockAppendError(s"References incorrect or non-existing block(liquid block exists): " + logDetails, block))
    }
  }.map { case ((bd, discarded)) => // finally place new as liquid
    micros.set(List.empty)
    baseB.set(Some(block))
    (bd, discarded)
  }
  }

  override def discardBlock(): Seq[Transaction] = write { implicit l =>
    baseB() match {
      case Some(block) =>
        baseB.set(None)
        micros.set(List.empty)
        block.transactionData
      case None =>
        inner.discardBlock()
    }
  }

  override def height(): Int = read { implicit l =>
    inner.height() + baseB().map(_ => 1).getOrElse(0)
  }

  override def blockBytes(height: Int): Option[Array[Byte]] = read { implicit l =>
    inner.blockBytes(height).orElse(if (height == inner.height() + 1) bestLiquidBlock().map(_.bytes) else None)
  }

  override def scoreOf(blockId: BlockId): Option[BlockchainScore] = read { implicit l =>
    inner.scoreOf(blockId)
      .orElse(if (containsLocalBlock(blockId))
        Some(inner.score() + baseB().get.blockScore)
      else None)
  }

  override def heightOf(blockId: BlockId): Option[Int] = read { implicit l =>
    lazy val innerHeight = inner.height()
    inner.heightOf(blockId).orElse(if (containsLocalBlock(blockId))
      Some(innerHeight + 1)
    else
      None)
  }

  override def lastBlockIds(howMany: Int): Seq[BlockId] = read { implicit l =>
    baseB() match {
      case Some(base) =>
        micros().headOption.map(_.totalResBlockSig).getOrElse(base.uniqueId) +: inner.lastBlockIds(howMany - 1)
      case None =>
        inner.lastBlockIds(howMany)
    }
  }

  override def appendMicroBlock(microBlock: MicroBlock)
                               (microBlockConsensusValidation: Long => Either[ValidationError, BlockDiff]): Either[ValidationError, BlockDiff] = write { implicit l =>
    baseB() match {
      case None =>
        Left(MicroBlockAppendError("No base block exists", microBlock))
      case Some(base) if base.signerData.generator.toAddress != microBlock.generator.toAddress =>
        Left(MicroBlockAppendError("Base block has been generated by another account", microBlock))
      case Some(base) =>
        micros().headOption match {
          case None if base.uniqueId != microBlock.prevResBlockSig =>
            blockMicroForkStats.increment()
            Left(MicroBlockAppendError("It's first micro and it doesn't reference base block(which exists)", microBlock))
          case Some(prevMicro) if prevMicro.totalResBlockSig != microBlock.prevResBlockSig =>
            microMicroForkStats.increment()
            Left(MicroBlockAppendError("It doesn't reference last known microBlock(which exists)", microBlock))
          case _ =>
            Signed.validateSignatures(microBlock)
              .flatMap(_ => microBlockConsensusValidation(base.timestamp))
              .map { microblockDiff =>
                micros.set(microBlock +: micros())
                microblockDiff
              }
        }
    }
  }

  private def containsLocalBlock(blockId: BlockId): Boolean = read { implicit l =>
    baseB().find(_.uniqueId == blockId)
      .orElse(micros().find(_.totalResBlockSig == blockId)).isDefined
  }

  private val forgeBlockTimeStats = Kamon.metrics.histogram("forge-block-time", Time.Milliseconds)

  def forgeBlock(id: BlockId): Option[(Block, DiscardedMicroBlocks)] = read { implicit l =>
    measureSuccessful(forgeBlockTimeStats, {
      baseB().flatMap(base => {
        val ms = micros().reverse
        if (base.uniqueId == id) {
          Some((base, ms))
        } else if (!ms.exists(_.totalResBlockSig == id)) None
        else {
          val (accumulatedTxs, maybeFound) = ms.foldLeft((List.empty[Transaction], Option.empty[(ByteStr, DiscardedMicroBlocks)])) { case ((accumulated, maybeDiscarded), micro) =>
            maybeDiscarded match {
              case Some((sig, discarded)) => (accumulated, Some((sig, micro +: discarded)))
              case None =>
                if (micro.totalResBlockSig == id)
                  (accumulated ++ micro.transactionData, Some((micro.totalResBlockSig, Seq.empty[MicroBlock])))
                else
                  (accumulated ++ micro.transactionData, None)
            }
          }
          maybeFound.map { case (sig, discardedMicroblocks) =>
            (
              base.copy(signerData = base.signerData.copy(signature = sig), transactionData = base.transactionData ++ accumulatedTxs),
              discardedMicroblocks
            )
          }
        }
      })
    })
  }

  override def microBlock(id: BlockId): Option[MicroBlock] = read { implicit l =>
    micros().find(_.totalResBlockSig == id)
  }

  override def close(): Unit = inner.close()

  override def lastBlockTimestamp(): Option[Long] = baseBlock().map(_.timestamp).orElse(inner.lastBlockTimestamp())

  override def lastBlockId(): Option[AssetId] = read { implicit l =>
    micros().headOption.map(_.totalResBlockSig)
      .orElse(baseB().map(_.uniqueId))
      .orElse(inner.lastBlockId())
  }

  private val blockMicroForkStats = Kamon.metrics.counter("block-micro-fork")

  private val microBlockForkStats = Kamon.metrics.counter("micro-block-fork")
  private val microBlockForkHeightStats = Kamon.metrics.histogram("micro-block-fork-height")

  private val microMicroForkStats = Kamon.metrics.counter("micro-micro-fork")

}
