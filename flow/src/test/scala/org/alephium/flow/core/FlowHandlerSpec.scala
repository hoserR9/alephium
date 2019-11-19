package org.alephium.flow.core

import scala.collection.mutable

import akka.testkit.TestProbe

import org.alephium.crypto.Keccak256
import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.model.{Block, ModelGen}

class FlowHandlerSpec extends AlephiumFlowActorSpec("FlowHandler") {
  import FlowHandler._

  behavior of "FlowHandlerState"

  def genPending(block: Block): PendingBlock = {
    genPending(block, mutable.HashSet.empty)
  }

  def genPending(missings: mutable.HashSet[Keccak256]): PendingBlock = {
    val block = ModelGen.blockGen.sample.get
    genPending(block, missings)
  }

  def genPending(block: Block, missings: mutable.HashSet[Keccak256]): PendingBlock = {
    PendingBlock(block, missings, DataOrigin.LocalMining, TestProbe().ref, TestProbe().ref)
  }

  trait StateFix {
    val block = ModelGen.blockGen.sample.get
  }

  it should "add status" in {
    val state = new FlowHandlerState { override def statusSizeLimit: Int = 2 }
    state.pendingStatus.size is 0

    val pending0 = genPending(mutable.HashSet.empty[Keccak256])
    state.addStatus(pending0)
    state.pendingStatus.size is 1
    state.pendingStatus.head._2 is pending0
    state.pendingStatus.last._2 is pending0
    state.counter is 1

    val pending1 = genPending(mutable.HashSet.empty[Keccak256])
    state.addStatus(pending1)
    state.pendingStatus.size is 2
    state.pendingStatus.head._2 is pending0
    state.pendingStatus.last._2 is pending1
    state.counter is 2

    val pending2 = genPending(mutable.HashSet.empty[Keccak256])
    state.addStatus(pending2)
    state.pendingStatus.size is 2
    state.pendingStatus.head._2 is pending1
    state.pendingStatus.last._2 is pending2
    state.counter is 3
  }

  it should "update status" in {
    val state  = new FlowHandlerState { override def statusSizeLimit: Int = 3 }
    val block0 = ModelGen.blockGen.sample.get
    val block1 = ModelGen.blockGen.sample.get
    val block2 = ModelGen.blockGen.sample.get

    val pending0 = genPending(block0, mutable.HashSet(block1.hash, block2.hash))
    state.addStatus(pending0)
    state.pendingStatus.size is 1
    state.pendingStatus.head._2.missingDeps.size is 2
    state.counter is 1

    val readies1 = state.updateStatus(block1.hash)
    readies1.size is 0
    state.pendingStatus.size is 1
    state.pendingStatus.head._2.missingDeps.size is 1
    state.counter is 1

    val readies2 = state.updateStatus(block2.hash).toList
    readies2.size is 1
    readies2.head is pending0
    state.pendingStatus.size is 0
    state.counter is 1
  }
}