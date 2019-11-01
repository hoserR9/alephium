package org.alephium.flow.core

import org.alephium.crypto.{ED25519Signature, Keccak256}
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Block, BlockHeader, TxOutput, TxOutputPoint}
import org.alephium.util.TimeStamp

object Validation {
  private type HeaderValidationResult = Either[Either[IOError, InvalidHeaderStatus], Unit]
  private type BlockValidationResult  = Either[Either[IOError, InvalidBlockStatus], Unit]

  private def invalid0(status: InvalidHeaderStatus): HeaderValidationResult = Left(Right(status))
  private def invalid1(status: InvalidBlockStatus): BlockValidationResult   = Left(Right(status))
  private val valid0: HeaderValidationResult                                = Right(())
  private val valid1: BlockValidationResult                                 = Right(())

  private def convert[T](x: Either[Either[IOError, T], Unit], default: T): IOResult[T] = x match {
    case Left(Left(error)) => Left(error)
    case Left(Right(t))    => Right(t)
    case Right(())         => Right(default)
  }

  def validate(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile
  ): IOResult[HeaderStatus] = {
    convert(validateHeader(header, flow, isSyncing), ValidHeader)
  }

  def validate(block: Block, flow: BlockFlow, isSyncing: Boolean)(
    implicit config: PlatformProfile): IOResult[BlockStatus] = {
    convert(validateBlock(block, flow, isSyncing), ValidBlock)
  }

  private def validateHeader(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): HeaderValidationResult = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- validateTimeStamp(header, isSyncing)
      _ <- validateWorkAmount(header)
      _ <- validateWorkTarget(header, headerChain)
      _ <- validateParent(header, headerChain)
      _ <- validateDeps(header, flow)
    } yield ()
  }

  private def validateBlock(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformProfile): BlockValidationResult = {
    for {
      _ <- validateGroup(block)
      _ <- validateHeader(block.header, flow, isSyncing)
      _ <- validateNonEmptyTransactions(block)
      _ <- validateCoinbase(block)
      _ <- validateMerkleRoot(block)
      _ <- validateTransactions(block, flow)
    } yield ()
  }

  def validateGroup(block: Block)(implicit config: PlatformProfile): BlockValidationResult = {
    if (block.chainIndex.relateTo(config.brokerInfo)) valid1
    else invalid1(InvalidGroup)
  }

  def validateTimeStamp(header: BlockHeader, isSyncing: Boolean): HeaderValidationResult = {
    val now      = TimeStamp.now()
    val headerTs = TimeStamp.fromMillis(header.timestamp)

    val ok1 = now <= now.plusHours(1)
    val ok2 = isSyncing || (headerTs >= now.plusHours(-1))
    if (ok1 && ok2) valid0 else invalid0(InvalidTimeStamp)
  }

  // Fix now: remove validateDiff from BlockHeader
  def validateWorkAmount(header: BlockHeader): HeaderValidationResult = {
    val current = BigInt(1, header.hash.bytes.toArray)
    assert(current >= 0)
    if (current <= header.target) valid0 else invalid0(InvalidWorkAmount)
  }

  def validateWorkTarget(header: BlockHeader,
                         headerChain: BlockHeaderChain): HeaderValidationResult = {
    headerChain.getHashTarget(header.hash) match {
      case Left(error)   => Left(Left(error))
      case Right(target) => if (target == header.target) valid0 else invalid0(InvalidWorkTarget)
    }
  }

  def validateParent(header: BlockHeader, headerChain: BlockHeaderChain)(
      implicit config: GroupConfig): HeaderValidationResult = {
    if (headerChain.contains(header.parentHash)) valid0 else invalid0(MissingParent)
  }

  def validateDeps(header: BlockHeader, flow: BlockFlow): HeaderValidationResult = {
    if (header.blockDeps.forall(flow.contains)) valid0 else invalid0(MissingDeps)
  }

  def validateNonEmptyTransactions(block: Block): BlockValidationResult = {
    if (block.transactions.nonEmpty) valid1 else invalid1(EmptyTransactionList)
  }

  def validateCoinbase(block: Block): BlockValidationResult = {
    val coinbase = block.transactions.head
    val unsigned = coinbase.unsigned
    if (unsigned.inputs.length == 0 && unsigned.outputs.length == 1 && coinbase.signature == ED25519Signature.zero)
      valid1
    else invalid1(InvalidCoinbase)
  }

  // TODO: use Merkle hash for transactions
  def validateMerkleRoot(block: Block): BlockValidationResult = {
    if (block.header.txsHash == Keccak256.hash(block.transactions)) valid1
    else invalid1(InvalidMerkleRoot)
  }

  // TODO: refine this and test this
  def validateTransactions(block: Block, flow: BlockFlow): BlockValidationResult = {
    val trie     = flow.getTrie(block)
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputPoint]
    block.transactions.foreach { tx =>
      tx.unsigned.inputs.foreach { txOutputPoint =>
        // scalastyle:off return
        if (utxoUsed.contains(txOutputPoint)) return invalid1(DoubleSpent)
        else {
          utxoUsed += txOutputPoint
          trie.getOpt[TxOutputPoint, TxOutput](txOutputPoint) match {
            case Left(error)        => return Left(Left(error))
            case Right(txOutputOpt) => if (txOutputOpt.isEmpty) return invalid1(InvalidCoins)
          }
        }
        // scalastyle:on return
      }
    }
    valid1
  }
}
