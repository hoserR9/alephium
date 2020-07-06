package org.alephium.protocol.vm

import scala.annotation.tailrec

import org.alephium.util.AVector

class Runtime[Ctx <: Context](val stack: Stack[Frame[Ctx]], var returnTo: AVector[Val])

object Runtime {
  def apply[Ctx <: Context](stack: Stack[Frame[Ctx]]): Runtime[Ctx] =
    new Runtime(stack, AVector.ofSize(0))
}

trait VM[Ctx <: Context] {
  def execute(ctx: Ctx,
              script: Script[Ctx],
              fields: AVector[Val],
              args: AVector[Val]): ExeResult[AVector[Val]] = {
    val stack = Stack.ofCapacity[Frame[Ctx]](frameStackMaxSize)
    val rt    = Runtime[Ctx](stack)

    stack.push(script.startFrame(ctx, fields, args, value => Right(rt.returnTo = value)))
    execute(stack).map(_ => rt.returnTo)
  }

  @tailrec
  private def execute(stack: Stack[Frame[Ctx]]): ExeResult[Unit] = {
    if (!stack.isEmpty) {
      val currentFrame = stack.topUnsafe
      if (currentFrame.isComplete) {
        stack.pop()
        execute(stack)
      } else {
        currentFrame.execute()
      }
    } else {
      Right(())
    }
  }
}

object StatelessVM extends VM[StatelessContext]

object StatefulVM extends VM[StatefulContext]
