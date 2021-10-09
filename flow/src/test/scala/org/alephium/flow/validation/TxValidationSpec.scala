// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.validation

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.{AlephiumFlowSpec, FlowFixture}
import org.alephium.flow.validation.ValidationStatus.{invalidTx, validTx}
import org.alephium.io.IOError
import org.alephium.protocol.{ALF, Hash, PrivateKey, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.model.ModelGenerators.AssetInputInfo
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{InvalidSignature => _, NetworkId => _, _}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AVector, TimeStamp, U256}

class TxValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  override val configValues = Map(("alephium.broker.broker-num", 1))

  trait Fixture extends TxValidation.Impl with VMFactory {
    val blockFlow = genesisBlockFlow()

    // TODO: prepare blockflow to test checkMempool
    def prepareWorldState(inputInfos: AVector[AssetInputInfo]): Unit = {
      inputInfos.foreach { inputInfo =>
        cachedWorldState.addAsset(inputInfo.txInput.outputRef, inputInfo.referredOutput) isE ()
      }
    }

    def checkBlockTx(
        tx: Transaction,
        preOutputs: AVector[AssetInputInfo],
        headerTs: TimeStamp = TimeStamp.now()
    ): TxValidationResult[Unit] = {
      prepareWorldState(preOutputs)
      for {
        chainIndex <- getChainIndex(tx)
        _          <- checkStateless(chainIndex, tx, checkDoubleSpending = true)
        _ <- checkStateful(
          chainIndex,
          tx,
          cachedWorldState,
          preOutputs.map(_.referredOutput),
          None,
          BlockEnv(networkConfig.networkId, headerTs, Target.Max)
        )
      } yield ()
    }

    def checkWitnesses(
        tx: Transaction,
        preOutputs: AVector[TxOutput]
    ): TxValidationResult[GasBox] = {
      val blockEnv = BlockEnv(networkConfig.networkId, TimeStamp.now(), Target.Max)
      checkGasAndWitnesses(tx, preOutputs, blockEnv)
    }

    def prepareOutput(lockup: LockupScript.Asset, unlock: UnlockScript) = {
      val group                 = lockup.groupIndex
      val (genesisPriKey, _, _) = genesisKeys(group.value)
      val block                 = transfer(blockFlow, genesisPriKey, lockup, ALF.alf(2))
      val output                = AVector(TxOutputInfo(lockup, ALF.alf(1), AVector.empty, None))
      addAndCheck(blockFlow, block)

      blockFlow.transfer(lockup, unlock, output, None, defaultGasPrice).rightValue.rightValue
    }

    def sign(unsigned: UnsignedTransaction, privateKeys: PrivateKey*): Transaction = {
      val signatures = privateKeys.map(SignatureSchema.sign(unsigned.hash.bytes, _))
      Transaction.from(unsigned, AVector.from(signatures))
    }

    def nestedValidator[T](
        validator: (Transaction) => TxValidationResult[T],
        preOutputs: AVector[AssetInputInfo]
    ): (Transaction) => TxValidationResult[T] = { (transaction: Transaction) =>
      {
        val result = validator(transaction)
        validateTxOnlyForTest(transaction, blockFlow)
        checkBlockTx(transaction, preOutputs)
        result
      }
    }

    implicit class RichTxValidationResult[T](res: TxValidationResult[T]) {
      def pass()                       = res.isRight is true
      def fail(error: InvalidTxStatus) = res.left.value isE error
    }

    implicit class RichTx(tx: Transaction) {
      def addAlfAmount(delta: U256): Transaction = {
        updateAlfAmount(_ + delta)
      }

      def zeroAlfAmount(): Transaction = {
        updateAlfAmount(_ => 0)
      }

      def updateAlfAmount(f: U256 => U256): Transaction = {
        replaceOutput(output => output.copy(amount = f(output.amount)))
      }

      def zeroTokenAmount(): Transaction = {
        replaceOutput(_.copy(tokens = AVector(Hash.generate -> U256.Zero)))
      }

      def inputs(inputs: AVector[TxInput]): Transaction = {
        tx.copy(unsigned = tx.unsigned.copy(inputs = inputs))
      }

      def gasPrice(gasPrice: GasPrice): Transaction = {
        tx.copy(unsigned = tx.unsigned.copy(gasPrice = gasPrice))
      }

      def gasAmount(gasAmount: GasBox): Transaction = {
        tx.copy(unsigned = tx.unsigned.copy(gasAmount = gasAmount))
      }

      def fixedOutputs(outputs: AVector[AssetOutput]): Transaction = {
        tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = outputs))
      }

      def getTokenAmount(tokenId: TokenId): U256 = {
        tx.unsigned.fixedOutputs.fold(U256.Zero) { case (acc, output) =>
          acc + output.tokens.filter(_._1 equals tokenId).map(_._2).reduce(_ + _)
        }
      }

      def sampleToken(): TokenId = {
        val tokens = tx.unsigned.fixedOutputs.flatMap(_.tokens.map(_._1))
        tokens.sample()
      }

      def getPreOutputs(
          worldState: MutableWorldState
      ): TxValidationResult[AVector[TxOutput]] = {
        worldState.getPreOutputs(tx) match {
          case Right(preOutputs)            => validTx(preOutputs)
          case Left(IOError.KeyNotFound(_)) => invalidTx(NonExistInput)
          case Left(error)                  => Left(Left(error))
        }
      }

      def modifyTokenAmount(tokenId: TokenId, f: U256 => U256): Transaction = {
        val fixedOutputs = tx.unsigned.fixedOutputs
        val relatedOutputIndexes = fixedOutputs
          .mapWithIndex { case (output, index) =>
            (index, output.tokens.exists(_._1 equals tokenId))
          }
          .map(_._1)
        val selected    = relatedOutputIndexes.sample()
        val output      = fixedOutputs(selected)
        val tokenIndex  = output.tokens.indexWhere(_._1 equals tokenId)
        val tokenAmount = output.tokens(tokenIndex)._2
        val outputNew =
          output.copy(tokens = output.tokens.replace(tokenIndex, tokenId -> f(tokenAmount)))
        tx.copy(unsigned =
          tx.unsigned.copy(fixedOutputs = fixedOutputs.replace(selected, outputNew))
        )
      }

      def replaceUnlock(unlock: UnlockScript, priKeys: PrivateKey*): Transaction = {
        val unsigned  = tx.unsigned
        val inputs    = unsigned.inputs
        val theInput  = inputs.head
        val newInputs = inputs.replace(0, theInput.copy(unlockScript = unlock))
        val newTx     = tx.copy(unsigned = unsigned.copy(inputs = newInputs))
        if (priKeys.isEmpty) {
          newTx
        } else {
          sign(newTx.unsigned, priKeys: _*)
        }
      }

      def pass[T]()(implicit validator: (Transaction) => TxValidationResult[T]) = {
        validator(tx).pass()
      }

      def fail[T](error: InvalidTxStatus)(implicit
          validator: (Transaction) => TxValidationResult[T]
      ): Assertion = {
        validator(tx).fail(error)
      }

      private def replaceOutput(f: AssetOutput => AssetOutput): Transaction = {
        val (index, output) = tx.unsigned.fixedOutputs.sampleWithIndex()
        val outputNew       = f(output)
        tx.copy(
          unsigned =
            tx.unsigned.copy(fixedOutputs = tx.unsigned.fixedOutputs.replace(index, outputNew))
        )
      }
    }
  }

  it should "pass valid transactions" in new Fixture {
    forAll(
      transactionGenWithPreOutputs(1, 1, chainIndexGen = chainIndexGenForBroker(brokerConfig))
    ) { case (tx, preOutputs) =>
      checkBlockTx(tx, preOutputs).pass()
    }
  }

  behavior of "Stateless Validation"

  it should "check network Id" in new Fixture {
    implicit val validator = validateTxOnlyForTest(_, blockFlow)

    val chainIndex = chainIndexGenForBroker(brokerConfig).sample.get
    val block      = transfer(blockFlow, chainIndex)
    val tx         = block.nonCoinbase.head
    tx.pass()

    tx.unsigned.networkId isnot NetworkId.AlephiumMainNet
    val invalidTx = tx.copy(unsigned = tx.unsigned.copy(networkId = NetworkId.AlephiumMainNet))
    invalidTx.fail(InvalidNetworkId)
  }

  it should "check too many inputs" in new Fixture {
    val tx    = transactionGen().sample.get
    val input = tx.unsigned.inputs.head

    val modified0         = tx.inputs(AVector.fill(ALF.MaxTxInputNum)(input))
    val modified1         = tx.inputs(AVector.fill(ALF.MaxTxInputNum + 1)(input))
    val contractOutputRef = ContractOutputRef.unsafe(Hint.unsafe(1), Hash.zero)
    val modified2         = tx.copy(contractInputs = AVector(contractOutputRef))

    {
      implicit val validator = checkInputNum(_, isIntraGroup = false)

      modified0.pass()
      modified1.fail(TooManyInputs)
      modified2.fail(ContractInputForInterGroupTx)
    }

    {
      implicit val validator = checkInputNum(_, isIntraGroup = true)

      modified0.pass()
      modified1.fail(TooManyInputs)
      modified2.pass()
    }
  }

  it should "check empty outputs" in new Fixture {
    forAll(transactionGenWithPreOutputs(1, 1)) { case (tx, preOutputs) =>
      implicit val validator =
        nestedValidator(checkOutputNum(_, tx.chainIndex.isIntraGroup), preOutputs)

      val unsignedNew = tx.unsigned.copy(fixedOutputs = AVector.empty)
      val txNew       = tx.copy(unsigned = unsignedNew)
      txNew.fail(NoOutputs)
    }
  }

  it should "check too many outputs" in new Fixture {
    val tx     = transactionGen().sample.get
    val output = tx.unsigned.fixedOutputs.head
    tx.generatedOutputs.isEmpty is true

    val maxGeneratedOutputsNum = ALF.MaxTxOutputNum - tx.outputsLength

    val modified0 = tx.fixedOutputs(AVector.fill(ALF.MaxTxOutputNum)(output))
    val modified1 = tx.fixedOutputs(AVector.fill(ALF.MaxTxOutputNum + 1)(output))
    val modified2 = tx.copy(generatedOutputs = AVector.fill(maxGeneratedOutputsNum)(output))
    val modified3 = tx.copy(generatedOutputs = AVector.fill(maxGeneratedOutputsNum + 1)(output))

    {
      implicit val validator = checkOutputNum(_, isIntraGroup = true)

      modified0.pass()
      modified1.fail(TooManyOutputs)
      modified2.pass()
      modified3.fail(TooManyOutputs)
    }

    {
      implicit val validator = checkOutputNum(_, isIntraGroup = false)

      modified0.pass()
      modified1.fail(TooManyOutputs)
      modified2.fail(GeneratedOutputForInterGroupTx)
    }
  }

  it should "check gas bounds" in new Fixture {
    implicit val validator = checkGasBound _

    val tx = transactionGen(1, 1).sample.get
    tx.pass()

    val txNew0 = tx.gasAmount(GasBox.unsafeTest(-1))
    txNew0.fail(InvalidStartGas)
    txNew0.fail(InvalidStartGas)(validateTxOnlyForTest(_, blockFlow))

    val txNew1 = tx.gasAmount(GasBox.unsafeTest(0))
    txNew1.fail(InvalidStartGas)
    txNew1.fail(InvalidStartGas)(validateTxOnlyForTest(_, blockFlow))

    val txNew2 = tx.gasAmount(minimalGas.use(1).rightValue)
    txNew2.fail(InvalidStartGas)
    txNew2.fail(InvalidStartGas)(validateTxOnlyForTest(_, blockFlow))

    tx.gasAmount(minimalGas).pass()
    tx.gasPrice(GasPrice(0)).fail(InvalidGasPrice)
    tx.gasPrice(GasPrice(ALF.MaxALFValue)).fail(InvalidGasPrice)
  }

  it should "check ALF balance overflow" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
        implicit val validator = nestedValidator(checkOutputStats, preOutputs)

        val alfAmount = tx.alfAmountInOutputs.get
        val delta     = U256.MaxValue - alfAmount + 1

        tx.addAlfAmount(delta).fail(BalanceOverFlow)
      }
    }
  }

  it should "check non-zero ALF amount for outputs" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.nonEmpty) {
        implicit val validator = nestedValidator(checkOutputStats, preOutputs)

        tx.zeroAlfAmount().fail(InvalidOutputStats)
      }
    }
  }

  it should "check non-zero token amount for outputs" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      whenever(tx.unsigned.fixedOutputs.nonEmpty) {
        implicit val validator = nestedValidator(checkOutputStats, preOutputs)

        tx.zeroTokenAmount().fail(InvalidOutputStats)
      }
    }
  }

  it should "check the number of tokens for outputs" in new Fixture {
    implicit val validator = checkOutputStats _

    val tx0 = transactionGen(numTokensGen = maxTokenPerUtxo + 1).sample.get
    tx0.fail(InvalidOutputStats)

    val tx1 = transactionGen(numTokensGen = maxTokenPerUtxo).sample.get
    tx1.pass()
  }

  it should "check the inputs indexes" in new Fixture {
    forAll(transactionGenWithPreOutputs(2, 5)) { case (tx, preOutputs) =>
      val chainIndex = tx.chainIndex
      val inputs     = tx.unsigned.inputs
      val localUnsignedGen =
        for {
          fromGroupNew <- groupIndexGen.retryUntil(!chainIndex.relateTo(_))
          scriptHint   <- scriptHintGen(fromGroupNew)
          selected     <- Gen.choose(0, inputs.length - 1)
        } yield {
          val input        = inputs(selected)
          val outputRefNew = AssetOutputRef.unsafeWithScriptHint(scriptHint, input.outputRef.key)
          val inputsNew    = inputs.replace(selected, input.copy(outputRef = outputRefNew))
          tx.unsigned.copy(inputs = inputsNew)
        }

      implicit val validator = nestedValidator(getChainIndex, preOutputs)
      forAll(localUnsignedGen) { unsignedNew =>
        tx.copy(unsigned = unsignedNew).fail(InvalidInputGroupIndex)
      }
    }
  }

  it should "check the output indexes" in new Fixture {
    forAll(transactionGenWithPreOutputs(2, 5)) { case (tx, preOutputs) =>
      val chainIndex = tx.chainIndex
      val outputs    = tx.unsigned.fixedOutputs
      whenever(
        !chainIndex.isIntraGroup && outputs.filter(_.toGroup equals chainIndex.to).length >= 2
      ) {
        val localUnsignedGen =
          for {
            toGroupNew      <- groupIndexGen.retryUntil(!chainIndex.relateTo(_))
            lockupScriptNew <- assetLockupGen(toGroupNew)
            selected        <- Gen.choose(0, outputs.length - 1)
          } yield {
            val outputNew  = outputs(selected).copy(lockupScript = lockupScriptNew)
            val outputsNew = outputs.replace(selected, outputNew)
            tx.unsigned.copy(fixedOutputs = outputsNew)
          }

        implicit val validator = nestedValidator(getChainIndex, preOutputs)
        forAll(localUnsignedGen) { unsignedNew =>
          tx.copy(unsigned = unsignedNew).fail(InvalidOutputGroupIndex)
        }
      }
    }
  }

  it should "check distinction of inputs" in new Fixture {
    forAll(transactionGenWithPreOutputs(1, 3)) { case (tx, preOutputs) =>
      implicit val validator = nestedValidator(checkUniqueInputs(_, true), preOutputs)

      val inputs      = tx.unsigned.inputs
      val unsignedNew = tx.unsigned.copy(inputs = inputs ++ inputs)
      val txNew       = tx.copy(unsigned = unsignedNew)

      txNew.fail(TxDoubleSpending)
    }
  }

  it should "check output data size" in new Fixture {
    private def modifyData0(outputs: AVector[AssetOutput], index: Int): AVector[AssetOutput] = {
      val dataNew = ByteString.fromArrayUnsafe(Array.fill(ALF.MaxOutputDataSize + 1)(0))
      dataNew.length is ALF.MaxOutputDataSize + 1
      val outputNew = outputs(index).copy(additionalData = dataNew)
      outputs.replace(index, outputNew)
    }

    private def modifyData1(outputs: AVector[TxOutput], index: Int): AVector[TxOutput] = {
      val dataNew = ByteString.fromArrayUnsafe(Array.fill(ALF.MaxOutputDataSize + 1)(0))
      dataNew.length is ALF.MaxOutputDataSize + 1
      val outputNew = outputs(index) match {
        case o: AssetOutput    => o.copy(additionalData = dataNew)
        case o: ContractOutput => o
      }
      outputs.replace(index, outputNew)
    }

    forAll(transactionGenWithPreOutputs(1, 3)) { case (tx, preOutputs) =>
      implicit val validator = nestedValidator(checkOutputDataSize, preOutputs)

      val outputIndex = Random.nextInt(tx.outputsLength)
      if (tx.getOutput(outputIndex).isInstanceOf[AssetOutput]) {
        val txNew = if (outputIndex < tx.unsigned.fixedOutputs.length) {
          val outputsNew = modifyData0(tx.unsigned.fixedOutputs, outputIndex)
          tx.copy(unsigned = tx.unsigned.copy(fixedOutputs = outputsNew))
        } else {
          val correctedIndex = outputIndex - tx.unsigned.fixedOutputs.length
          val outputsNew     = modifyData1(tx.generatedOutputs, correctedIndex)
          tx.copy(generatedOutputs = outputsNew)
        }

        txNew.fail(OutputDataSizeExceeded)
      }
    }
  }

  behavior of "stateful validation"

  it should "get previous outputs of tx inputs" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, inputInfos) =>
      prepareWorldState(inputInfos)
      tx.getPreOutputs(cachedWorldState) isE inputInfos.map(_.referredOutput).as[TxOutput]
    }
  }

  it should "check lock time" in new Fixture {
    val currentTs = TimeStamp.now()
    val futureTs  = currentTs.plusMillisUnsafe(1)
    forAll(transactionGenWithPreOutputs(lockTimeGen = Gen.const(currentTs))) {
      case (_, preOutputs) =>
        checkLockTime(preOutputs.map(_.referredOutput), TimeStamp.zero).fail(TimeLockedTx)
        checkLockTime(preOutputs.map(_.referredOutput), currentTs).pass()
        checkLockTime(preOutputs.map(_.referredOutput), futureTs).pass()
    }
    forAll(transactionGenWithPreOutputs(lockTimeGen = Gen.const(futureTs))) {
      case (_, preOutputs) =>
        checkLockTime(preOutputs.map(_.referredOutput), TimeStamp.zero).fail(TimeLockedTx)
        checkLockTime(preOutputs.map(_.referredOutput), currentTs).fail(TimeLockedTx)
        checkLockTime(preOutputs.map(_.referredOutput), futureTs).pass()
    }
  }

  it should "test both ALF and token balances" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      checkAlfBalance(tx, preOutputs.map(_.referredOutput), None).pass()
      checkTokenBalance(tx, preOutputs.map(_.referredOutput)).pass()
      checkBlockTx(tx, preOutputs).pass()
    }
  }

  it should "validate ALF balances" in new Fixture {
    forAll(transactionGenWithPreOutputs()) { case (tx, preOutputs) =>
      implicit val validator = nestedValidator(
        checkAlfBalance(_, preOutputs.map(_.referredOutput), None),
        preOutputs
      )

      tx.addAlfAmount(1).fail(InvalidAlfBalance)
    }
  }

  it should "test token balance overflow" in new Fixture {
    forAll(transactionGenWithPreOutputs(tokensNumGen = Gen.choose(1, 10))) {
      case (tx, preOutputs) =>
        implicit val validator = nestedValidator(
          checkTokenBalance(_, preOutputs.map(_.referredOutput)),
          preOutputs
        )

        whenever(tx.unsigned.fixedOutputs.length >= 2) { // only able to overflow 2 outputs
          val tokenId     = tx.sampleToken()
          val tokenAmount = tx.getTokenAmount(tokenId)

          tx.modifyTokenAmount(tokenId, U256.MaxValue - tokenAmount + 1 + _).fail(BalanceOverFlow)
        }
    }
  }

  it should "validate token balances" in new Fixture {
    forAll(transactionGenWithPreOutputs(tokensNumGen = Gen.choose(1, 10))) {
      case (tx, preOutputs) =>
        implicit val validator = nestedValidator(
          checkTokenBalance(_, preOutputs.map(_.referredOutput)),
          preOutputs
        )

        val tokenId = tx.sampleToken()
        tx.modifyTokenAmount(tokenId, _ + 1).fail(InvalidTokenBalance)
    }
  }

  it should "check the exact gas cost" in new Fixture {
    import GasSchedule._

    val chainIndex  = chainIndexGenForBroker(brokerConfig).sample.get
    val block       = transfer(blockFlow, chainIndex)
    val tx          = block.nonCoinbase.head
    val blockEnv    = BlockEnv.from(block.header)
    val worldState  = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
    val prevOutputs = worldState.getPreOutputs(tx).rightValue

    val initialGas = tx.unsigned.gasAmount
    val gasLeft    = checkGasAndWitnesses(tx, prevOutputs, blockEnv).rightValue
    val gasUsed    = initialGas.use(gasLeft).rightValue
    gasUsed is GasBox.unsafe(14060)
    gasUsed is (txBaseGas addUnsafe txInputBaseGas addUnsafe txOutputBaseGas.mulUnsafe(
      2
    ) addUnsafe GasSchedule.p2pkUnlockGas)
  }

  it should "validate witnesses" in new Fixture {
    import ModelGenerators.ScriptPair
    forAll(transactionGenWithPreOutputs(1, 1)) { case (tx, preOutputs) =>
      val inputsState              = preOutputs.map(_.referredOutput)
      val ScriptPair(_, unlock, _) = p2pkScriptGen(GroupIndex.unsafe(1)).sample.get
      val unsigned                 = tx.unsigned
      val inputs                   = unsigned.inputs
      val preparedWorldState       = preOutputs

      implicit val validator = nestedValidator(
        checkWitnesses(_, inputsState.as[TxOutput]),
        preparedWorldState
      )

      {
        val txNew = tx.copy(inputSignatures = tx.inputSignatures.init)
        txNew.fail(NotEnoughSignature)
      }

      {
        val (sampleIndex, sample) = inputs.sampleWithIndex()
        val inputNew              = sample.copy(unlockScript = unlock)
        val inputsNew             = inputs.replace(sampleIndex, inputNew)
        val txNew                 = tx.copy(unsigned = unsigned.copy(inputs = inputsNew))
        txNew.fail(InvalidPublicKeyHash)
      }

      {
        val signature        = Signature.generate
        val (sampleIndex, _) = tx.inputSignatures.sampleWithIndex()
        val signaturesNew    = tx.inputSignatures.replace(sampleIndex, signature)
        val txNew            = tx.copy(inputSignatures = signaturesNew)
        txNew.fail(InvalidSignature)
      }

      {
        val txNew = tx.copy(inputSignatures = tx.inputSignatures ++ tx.inputSignatures)
        txNew.fail(TooManySignatures)
      }
    }
  }

  it should "compress signatures" in new Fixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val tx         = transfer(blockFlow, chainIndex).nonCoinbase.head
    val unsigned1  = tx.unsigned.copy(inputs = tx.unsigned.inputs ++ tx.unsigned.inputs)
    val tx1        = Transaction.from(unsigned1, genesisKeys(0)._1)
    val preOutputs =
      blockFlow
        .getBestPersistedWorldState(chainIndex.from)
        .rightValue
        .getPreOutputs(tx1)
        .rightValue

    implicit val validator = checkWitnesses(_: Transaction, preOutputs)

    tx1.unsigned.inputs.length is 2
    tx1.inputSignatures.length is 1
    tx1.pass()

    val tx2 = tx1.copy(inputSignatures = tx1.inputSignatures ++ tx1.inputSignatures)
    tx2.unsigned.inputs.length is 2
    tx2.inputSignatures.length is 2
    tx2.fail(TooManySignatures)
  }

  behavior of "lockup script"

  it should "validate p2pkh" in new Fixture {
    implicit val validator = validateTxOnlyForTest(_, blockFlow)

    forAll(keypairGen) { case (priKey, pubKey) =>
      val lockup   = LockupScript.p2pkh(pubKey)
      val unlock   = UnlockScript.p2pkh(pubKey)
      val unsigned = prepareOutput(lockup, unlock)
      val tx       = Transaction.from(unsigned, priKey)

      tx.pass()
      tx.replaceUnlock(UnlockScript.p2pkh(PublicKey.generate)).fail(InvalidPublicKeyHash)
      tx.copy(inputSignatures = AVector(Signature.generate)).fail(InvalidSignature)
    }
  }

  it should "invalidate p2mpkh" in new Fixture {
    val (priKey0, pubKey0) = keypairGen.sample.get
    val (priKey1, pubKey1) = keypairGen.sample.get
    val (_, pubKey2)       = keypairGen.sample.get

    def tx(keys: (PublicKey, Int)*): Transaction = {
      val lockup   = LockupScript.p2mpkhUnsafe(AVector(pubKey0, pubKey1, pubKey2), 2)
      val unlock   = UnlockScript.p2mpkh(AVector.from(keys))
      val unsigned = prepareOutput(lockup, unlock)
      sign(unsigned, priKey0, priKey1)
    }

    implicit val validator = validateTxOnlyForTest(_, blockFlow)

    tx(pubKey0 -> 0).fail(InvalidNumberOfPublicKey)
    tx(pubKey0 -> 0, pubKey1 -> 1, pubKey2 -> 2).fail(InvalidNumberOfPublicKey)
    tx(pubKey1 -> 1, pubKey0 -> 0).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey1 -> 0, pubKey0 -> 0).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey1 -> 1, pubKey0 -> 1).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey0 -> 0, pubKey1 -> 3).fail(InvalidP2mpkhUnlockScript)
    tx(pubKey0 -> 0, pubKey1 -> 2).fail(InvalidPublicKeyHash)
    tx(pubKey1 -> 0, pubKey1 -> 1).fail(InvalidPublicKeyHash)
    tx(pubKey0 -> 0, pubKey2 -> 2).fail(InvalidSignature)
    tx(pubKey0 -> 0, pubKey1 -> 1).pass()
  }

  it should "validate p2sh" in new Fixture {
    // scalastyle:off no.equal
    def rawScript(n: Int) =
      s"""
         |AssetScript P2sh {
         |  pub fn main(a: U256) -> () {
         |    assert!(a == $n)
         |  }
         |}
         |""".stripMargin
    // scalastyle:on no.equal

    val script   = Compiler.compileAssetScript(rawScript(51)).rightValue
    val lockup   = LockupScript.p2sh(script)
    val unlock   = UnlockScript.p2sh(script, AVector(Val.U256(51)))
    val unsigned = prepareOutput(lockup, unlock)

    implicit val validator = validateTxOnlyForTest(_, blockFlow)

    val tx0 = Transaction.from(unsigned, AVector.empty[Signature])
    tx0.pass()

    val tx1 = tx0.replaceUnlock(UnlockScript.p2sh(script, AVector(Val.U256(50))))
    tx1.fail(UnlockScriptExeFailed(AssertionFailed))

    val newScript = Compiler.compileAssetScript(rawScript(50)).rightValue
    val tx2       = tx0.replaceUnlock(UnlockScript.p2sh(newScript, AVector(Val.U256(50))))
    tx2.fail(InvalidScriptHash)
  }

  trait GasFixture extends Fixture {
    def groupIndex: GroupIndex
    def tx: Transaction
    lazy val initialGas = minimalGas
    lazy val blockEnv =
      BlockEnv(NetworkId.AlephiumMainNet, TimeStamp.now(), consensusConfig.maxMiningTarget)
    lazy val prevOutputs = blockFlow
      .getBestPersistedWorldState(groupIndex)
      .rightValue
      .getPreOutputs(tx)
      .rightValue
      .asUnsafe[AssetOutput]
    lazy val txEnv = TxEnv(tx, prevOutputs, Stack.ofCapacity[Signature](0))
  }

  it should "charge gas for asset script size" in new GasFixture {
    val rawScript =
      s"""
         |AssetScript P2sh {
         |  pub fn main() -> () {
         |    return
         |  }
         |}
         |""".stripMargin

    val script   = Compiler.compileAssetScript(rawScript).rightValue
    val lockup   = LockupScript.p2sh(script)
    val unlock   = UnlockScript.p2sh(script, AVector(Val.U256(51)))
    val unsigned = prepareOutput(lockup, unlock)
    val tx       = Transaction.from(unsigned, AVector.empty[Signature])

    val groupIndex = lockup.groupIndex
    val gasRemaining =
      checkUnlockScript(blockEnv, txEnv, initialGas, script.hash, script, AVector.empty).rightValue
    initialGas is gasRemaining.addUnsafe(
      script.bytes.size + GasHash.gas(script.bytes.size).value + 200 /* 200 is the call gas */
    )
  }

  it should "charge gas for tx script size" in new GasFixture {
    val rawScript =
      s"""
         |TxScript P2sh {
         |  pub fn main() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val script     = Compiler.compileTxScript(rawScript).rightValue
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = simpleScript(blockFlow, chainIndex, script)
    val tx         = block.nonCoinbase.head
    val groupIndex = GroupIndex.unsafe(0)

    val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
    val gasRemaining =
      checkTxScript(chainIndex, tx, initialGas, worldState, prevOutputs, blockEnv).rightValue
    initialGas is gasRemaining.addUnsafe(script.bytes.size + 200 /* 200 is the call gas */ )
    val noScriptTx = tx.copy(unsigned = tx.unsigned.copy(scriptOpt = None))
    checkTxScript(
      chainIndex,
      noScriptTx,
      initialGas,
      worldState,
      prevOutputs,
      blockEnv
    ).rightValue is initialGas
  }

  it should "validate mempool tx fully" in new FlowFixture {
    val txValidator = TxValidation.build
    txValidator
      .validateMempoolTxTemplate(outOfGasTxTemplate, blockFlow)
      .leftValue isE TxScriptExeFailed(OutOfGas)
  }
}
