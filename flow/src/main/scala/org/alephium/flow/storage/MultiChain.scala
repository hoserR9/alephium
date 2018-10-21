package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.ChainIndex
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

trait MultiChain extends BlockPool {
  implicit val config: PlatformConfig

  def getChain(chainIndex: ChainIndex): BlockChain

  def getChain(block: Block): BlockChain = getChain(getIndex(block))

  def getChain(hash: Keccak256): BlockChain = getChain(getIndex(hash))

  def getIndex(block: Block): ChainIndex = {
    getIndex(block.hash)
  }

  def contains(hash: Keccak256): Boolean = {
    val chain = getChain(hash)
    chain.contains(hash)
  }

  def getIndex(hash: Keccak256): ChainIndex = {
    ChainIndex.fromHash(hash)
  }

  def add(block: Block): AddBlockResult

  def getBlock(hash: Keccak256): Block = {
    getChain(hash).getBlock(hash)
  }

  def getBlocks(locator: Keccak256): AVector[Block] = {
    getChain(locator).getBlocks(locator)
  }

  def isTip(hash: Keccak256): Boolean = {
    getChain(hash).isTip(hash)
  }

  def getHeight(hash: Keccak256): Int = {
    getChain(hash).getHeight(hash)
  }

  def getWeight(hash: Keccak256): Int = {
    getChain(hash).getWeight(hash)
  }

  def getBlockSlice(hash: Keccak256): AVector[Block] = getChain(hash).getBlockSlice(hash)
}
