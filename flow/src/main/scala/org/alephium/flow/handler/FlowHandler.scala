package org.alephium.flow.handler

import scala.collection.mutable

import akka.actor.Props

import org.alephium.flow.client.Miner
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.message.{Message, SendHeaders}
import org.alephium.protocol.model._
import org.alephium.util._

object FlowHandler {
  def props(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(
      implicit brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig): Props =
    Props(new FlowHandler(blockFlow, eventBus))

  sealed trait Command
  final case class AddHeader(header: BlockHeader,
                             broker: ActorRefT[ChainHandler.Event],
                             origin: DataOrigin)
      extends Command
  final case class AddBlock(block: Block, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin)
      extends Command
  final case class GetBlocks(locators: AVector[Hash])                           extends Command
  final case class GetHeaders(locators: AVector[Hash])                          extends Command
  final case class GetSyncInfo(remoteBroker: BrokerInfo, isSameClique: Boolean) extends Command
  final case class GetSyncData(blockLocators: AVector[Hash], headerLocators: AVector[Hash])
      extends Command
  final case class PrepareBlockFlow(chainIndex: ChainIndex)  extends Command
  final case class Register(miner: ActorRefT[Miner.Command]) extends Command
  case object UnRegister                                     extends Command

  sealed trait PendingData {
    def missingDeps: mutable.HashSet[Hash]
  }
  final case class PendingBlock(block: Block,
                                missingDeps: mutable.HashSet[Hash],
                                origin: DataOrigin,
                                broker: ActorRefT[ChainHandler.Event],
                                chainHandler: ActorRefT[BlockChainHandler.Command])
      extends PendingData
      with Command
  final case class PendingHeader(header: BlockHeader,
                                 missingDeps: mutable.HashSet[Hash],
                                 origin: DataOrigin,
                                 broker: ActorRefT[ChainHandler.Event],
                                 chainHandler: ActorRefT[HeaderChainHandler.Command])
      extends PendingData
      with Command

  sealed trait Event
  final case class BlockFlowTemplate(index: ChainIndex,
                                     height: Int,
                                     deps: AVector[Hash],
                                     target: BigInt,
                                     transactions: AVector[Transaction])
      extends Event
  final case class BlocksLocated(blocks: AVector[Block])                           extends Event
  final case class SyncData(blocks: AVector[Block], headers: AVector[BlockHeader]) extends Event
  final case class SyncInfo(blockLocators: AVector[Hash], headerLocators: AVector[Hash])
      extends Event
  final case class BlockAdded(block: Block,
                              broker: ActorRefT[ChainHandler.Event],
                              origin: DataOrigin)
      extends Event
  final case class HeaderAdded(header: BlockHeader,
                               broker: ActorRefT[ChainHandler.Event],
                               origin: DataOrigin)
      extends Event
  final case class BlockNotify(header: BlockHeader, height: Int) extends EventBus.Event
}

// TODO: set AddHeader and AddBlock with highest priority
// Queue all the work related to miner, rpc server, etc. in this actor
class FlowHandler(blockFlow: BlockFlow, eventBus: ActorRefT[EventBus.Message])(
    implicit brokerConfig: BrokerConfig,
    consensusConfig: ConsensusConfig)
    extends BaseActor
    with FlowHandlerState {
  import FlowHandler._

  override def statusSizeLimit: Int = brokerConfig.brokerNum * 8

  override def receive: Receive = handleWith(None)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def handleWith(minerOpt: Option[ActorRefT[Miner.Command]]): Receive =
    handleRelay(minerOpt) orElse handleSync

  def handleRelay(minerOpt: Option[ActorRefT[Miner.Command]]): Receive = {
    case GetHeaders(locators) =>
      locators.flatMapE(blockFlow.getHeadersAfter) match {
        case Left(error) =>
          log.warning(s"Failure while getting block headers: $error")
        case Right(headers) =>
          sender() ! Message(SendHeaders(headers))
      }
    case GetBlocks(locators: AVector[Hash]) =>
      locators.flatMapE(blockFlow.getBlocksAfter) match {
        case Left(error) =>
          log.warning(s"IO Failure while getting blocks: $error")
        case Right(blocks) =>
          sender() ! BlocksLocated(blocks)
      }
    case PrepareBlockFlow(chainIndex) => prepareBlockFlow(chainIndex)
    case AddHeader(header, broker, origin: DataOrigin) =>
      handleHeader(minerOpt, header, broker, origin)
    case AddBlock(block, broker, origin) =>
      handleBlock(minerOpt, block, broker, origin)
    case pending: PendingData => handlePending(pending)
    case Register(miner)      => context become handleWith(Some(miner))
    case UnRegister           => context become handleWith(None)
  }

  def handleSync: Receive = {
    case GetSyncInfo(remoteBroker, isSameClique) =>
      val info = if (isSameClique) {
        blockFlow.getIntraCliqueSyncInfo(remoteBroker)
      } else {
        blockFlow.getInterCliqueSyncInfo(remoteBroker)
      }
      sender() ! SyncInfo(info.blockLocators, info.headerLocators)

    case GetSyncData(blockLocators, headerLocators) =>
      val result = for {
        blocks  <- blockLocators.flatMapE(blockFlow.getBlocksAfter)
        headers <- headerLocators.flatMapE(blockFlow.getHeadersAfter)
      } yield SyncData(blocks, headers)
      result match {
        case Left(error)     => handleIOError(error)
        case Right(syncData) => sender() ! syncData
      }
  }

  def prepareBlockFlow(chainIndex: ChainIndex): Unit = {
    assume(brokerConfig.contains(chainIndex.from))
    val template = blockFlow.prepareBlockFlow(chainIndex)
    template match {
      case Left(error) =>
        log.warning(s"Failure while computing best dependencies: ${error.toString}")
      case Right(message) =>
        sender() ! message
    }
  }

  def handleHeader(minerOpt: Option[ActorRefT[Miner.Command]],
                   header: BlockHeader,
                   broker: ActorRefT[ChainHandler.Event],
                   origin: DataOrigin): Unit = {
    blockFlow.contains(header) match {
      case Right(true) =>
        log.debug(s"Blockheader ${header.shortHex} exists already")
      case Right(false) =>
        blockFlow.add(header) match {
          case Left(error) => handleIOError(error)
          case Right(_) =>
            sender() ! FlowHandler.HeaderAdded(header, broker, origin)
            updateUponNewData(header.hash)
            minerOpt.foreach(_ ! Miner.UpdateTemplate)
            logInfo(header)
        }
      case Left(error) => handleIOError(error)
    }
  }

  def handleBlock(minerOpt: Option[ActorRefT[Miner.Command]],
                  block: Block,
                  broker: ActorRefT[ChainHandler.Event],
                  origin: DataOrigin): Unit = {
    escapeIOError(blockFlow.contains(block)) { isIncluded =>
      if (!isIncluded) {
        blockFlow.add(block) match {
          case Left(error) => handleIOError(error)
          case Right(_) =>
            sender() ! FlowHandler.BlockAdded(block, broker, origin)
            updateUponNewData(block.hash)
            origin match {
              case DataOrigin.Local =>
                minerOpt.foreach(_ ! Miner.MinedBlockAdded(block.chainIndex))
              case _: DataOrigin.FromClique =>
                minerOpt.foreach(_ ! Miner.UpdateTemplate)
            }
            logInfo(block.header)
            notify(block)
        }
      }
    }
  }

  def notify(block: Block): Unit = {
    escapeIOError(blockFlow.getHeight(block)) { height =>
      eventBus ! BlockNotify(block.header, height)
    }
  }

  def handlePending(pending: PendingData): Unit = {
    val missings = pending.missingDeps
    escapeIOError(IOUtils.tryExecute(missings.filterInPlace(!blockFlow.containsUnsafe(_)))) { _ =>
      if (missings.isEmpty) {
        feedback(pending)
      } else {
        addStatus(pending)
      }
    }
  }

  def updateUponNewData(hash: Hash): Unit = {
    val readies = updateStatus(hash)
    if (readies.nonEmpty) {
      log.debug(s"There are #${readies.size} pending blocks/header ready for further processing")
    }
    readies.foreach(feedback)
  }

  def feedback(pending: PendingData): Unit = pending match {
    case PendingBlock(block, _, origin, broker, chainHandler) =>
      chainHandler ! BlockChainHandler.AddPendingBlock(block, broker, origin)
    case PendingHeader(header, _, origin, broker, chainHandler) =>
      chainHandler ! HeaderChainHandler.AddPendingHeader(header, broker, origin)
  }

  def logInfo(header: BlockHeader): Unit = {
    val total = blockFlow.numHashes
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val heights = for {
      i <- 0 until brokerConfig.groups
      j <- 0 until brokerConfig.groups
      height = blockFlow.getHashChain(ChainIndex.unsafe(i, j)).maxHeight.getOrElse(-1)
    } yield s"$i-$j:$height"
    val heightsInfo = heights.mkString(", ")
    val targetRatio =
      (BigDecimal(header.target) / BigDecimal(consensusConfig.maxMiningTarget)).toFloat
    val timeSpan = {
      chain.getBlockHeader(header.parentHash) match {
        case Left(_) => "?ms"
        case Right(parentHeader) =>
          val span = header.timestamp.millis - parentHeader.timestamp.millis
          s"${span}ms"
      }
    }
    log.info(s"$index; total: $total; ${chain
      .show(header.hash)}; heights: $heightsInfo; targetRatio: $targetRatio, timeSpan: $timeSpan")
  }

  // TODO: improve error handling
  def handleIOError(error: IOError): Unit = {
    log.debug(s"IO failed in flow handler: ${error.toString}")
  }

  def escapeIOError[T](result: IOResult[T])(f: T => Unit): Unit = {
    result match {
      case Right(t) => f(t)
      case Left(e)  => handleIOError(e)
    }
  }
}

trait FlowHandlerState {
  import FlowHandler._

  def statusSizeLimit: Int

  var counter: Int  = 0
  val pendingStatus = scala.collection.mutable.SortedMap.empty[Int, PendingData]

  def increaseAndCounter(): Int = {
    counter += 1
    counter
  }

  def addStatus(pending: PendingData): Unit = {
    pendingStatus.put(increaseAndCounter(), pending)
    checkSizeLimit()
  }

  def updateStatus(hash: Hash): IndexedSeq[PendingData] = {
    val toRemove = pendingStatus.collect[Int] {
      case (ts, status) if status.missingDeps.remove(hash) && status.missingDeps.isEmpty =>
        ts
    }
    val blocks = toRemove.map(pendingStatus(_))
    toRemove.foreach(pendingStatus.remove)
    blocks.toIndexedSeq
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def checkSizeLimit(): Unit = {
    if (pendingStatus.size > statusSizeLimit) {
      val toRemove = pendingStatus.head._1
      pendingStatus.remove(toRemove)
      ()
    }
  }
}