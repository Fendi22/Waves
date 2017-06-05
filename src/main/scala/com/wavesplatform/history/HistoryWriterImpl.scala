package com.wavesplatform.history

import java.util.concurrent.locks.ReentrantReadWriteLock

import com.wavesplatform.state2.{ByteStr, ByteStrDataType}
import org.h2.mvstore.MVStore
import scorex.account.Account
import scorex.block.Block
import scorex.transaction.History.BlockchainScore
import scorex.transaction.ValidationError.CustomError
import scorex.transaction.{HistoryWriter, ValidationError}
import scorex.utils.{LogMVMapBuilder, ScorexLogging}

class HistoryWriterImpl private(db: MVStore, val synchronizationToken: ReentrantReadWriteLock) extends HistoryWriter with ScorexLogging {
  private val blockBodyByHeight = Synchronized(db.openMap("blocks", new LogMVMapBuilder[Int, Array[Byte]]))
  private val blockIdByHeight = Synchronized(db.openMap("signatures", new LogMVMapBuilder[Int, ByteStr].valueType(new ByteStrDataType)))
  private val heightByBlockId = Synchronized(db.openMap("signaturesReverse", new LogMVMapBuilder[ByteStr, Int].keyType(new ByteStrDataType)))
  private val scoreByHeight = Synchronized(db.openMap("score", new LogMVMapBuilder[Int, BigInt]))

  override def appendBlock(block: Block): Either[ValidationError, Unit] = write { implicit lock =>
    if ((height() == 0) || (this.lastBlock.uniqueId == block.reference)) {
      val h = height() + 1
      blockBodyByHeight.mutate(_.put(h, block.bytes))
      scoreByHeight.mutate(_.put(h, score() + block.blockScore))
      blockIdByHeight.mutate(_.put(h, block.uniqueId))
      heightByBlockId.mutate(_.put(block.uniqueId, h))
      db.commit()
      Right(())
    } else {
      Left(CustomError(s"Failed to append block ${block.encodedId} which parent(${block.reference.base58} is not last block in blockchain"))
    }
  }

  override def discardBlock(): Unit = write { implicit lock =>
    val h = height()
    blockBodyByHeight.mutate(_.remove(h))
    scoreByHeight.mutate(_.remove(h))
    val vOpt = Option(blockIdByHeight.mutate(_.remove(h)))
    vOpt.map(v => heightByBlockId.mutate(_.remove(v)))
    db.commit()
  }

  override def blockAt(height: Int): Option[Block] = read { implicit lock =>
    Option(blockBodyByHeight().get(height)).map(Block.parseBytes(_).get)
  }

  override def lastBlockIds(howMany: Int): Seq[ByteStr] = read { implicit lock =>
    (Math.max(1, height() - howMany + 1) to height()).flatMap(i => Option(blockIdByHeight().get(i)))
      .reverse
  }

  override def height(): Int = read { implicit lock => blockIdByHeight().size() }

  override def score(): BlockchainScore = read { implicit lock =>
    if (height() > 0) scoreByHeight().get(height()) else 0
  }

  override def scoreOf(id: ByteStr): BlockchainScore = read { implicit lock =>
    heightOf(id).map(scoreByHeight().get(_)).getOrElse(0)
  }

  override def heightOf(blockSignature: ByteStr): Option[Int] = read { implicit lock =>
    Option(heightByBlockId().get(blockSignature))
  }

  override def generatedBy(account: Account, from: Int, to: Int): Seq[Block] = read { implicit lock =>
    for {
      h <- from to to
      block <- blockAt(h)
      if block.signerData.generator.address.equals(account.address)
    } yield block
  }

  override def blockBytes(height: Int): Option[Array[Byte]] = read { implicit lock =>
    Option(blockBodyByHeight().get(height))
  }
}

object HistoryWriterImpl {
  def apply(db: MVStore, synchronizationToken: ReentrantReadWriteLock): Either[String, HistoryWriterImpl] = {
    val h = new HistoryWriterImpl(db, synchronizationToken)
    h.read { implicit lock =>
      if (Set(h.blockBodyByHeight().size(), h.blockIdByHeight().size(), h.heightByBlockId().size(), h.scoreByHeight().size()).size != 1)
        Left("Block storage is inconsistent")
      else
        Right(h)
    }
  }
}
