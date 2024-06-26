package lamp.nn

import lamp.autograd.{Variable, Constant, BatchNorm2D => BN, param, const}
import lamp.Sc
import lamp.scope
import lamp.STen
import lamp.STenOptions

case class BatchNorm2D(
    weight: Constant,
    bias: Constant,
    runningMean: Constant,
    runningVar: Constant,
    training: Boolean,
    momentum: Double,
    eps: Double
) extends Module {

  override val state = List(
    weight -> BatchNorm.Weights,
    bias -> BatchNorm.Bias,
    runningMean -> BatchNorm.RunningMean,
    runningVar -> BatchNorm.RunningVar
  )

  override def forward[S: Sc](x: Variable): Variable =
    new BN(
      scope,
      x,
      weight,
      bias,
      runningMean.value,
      runningVar.value,
      training,
      momentum,
      eps
    ).value

}

object BatchNorm2D {
  implicit val trainingMode : TrainingMode[BatchNorm2D] = TrainingMode.make[BatchNorm2D](
    asEval1 = m => m.copy(training = false),
    asTraining1 = m => m.copy(training = true)
  )
  implicit val load : Load[BatchNorm2D] = Load.make[BatchNorm2D](m =>
    tensors => {
      m.weight.value.copyFrom(tensors.head)
      m.bias.value.copyFrom(tensors(1))
      m.runningMean.value.copyFrom(tensors(2))
      m.runningVar.value.copyFrom(tensors(3))
    }
  )
  case object Weights extends LeafTag
  case object Bias extends LeafTag
  def apply[S: Sc](
      features: Int,
      tOpt: STenOptions,
      training: Boolean = true,
      momentum: Double = 0.1,
      eps: Double = 1e-5
  ): BatchNorm2D = BatchNorm2D(
    weight = param(STen.normal(0.0, 0.01, List(features.toLong), tOpt)),
    bias = param(STen.zeros(List(features.toLong), tOpt)),
    runningMean = const(STen.zeros(List(features.toLong), tOpt)),
    runningVar = const(STen.zeros(List(features.toLong), tOpt)),
    training = training,
    momentum = momentum,
    eps = eps
  )
}
