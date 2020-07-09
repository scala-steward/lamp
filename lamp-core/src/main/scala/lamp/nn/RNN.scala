package lamp.nn

import lamp.autograd.{Variable, param, const}
import aten.Tensor
import aten.ATen
import scala.collection.mutable
import lamp.autograd.ConcatenateAddNewDim
import aten.TensorOptions
import cats.effect.concurrent.Ref
import cats.effect.IO
import lamp.autograd.AllocatedVariablePool

/** Inputs of size (sequence length * batch * in dim)
  * Outputs of size (sequence length * batch * hidden dim)
  */
case class RNN(
    weightXh: Variable,
    weightHh: Variable,
    biasH: Variable
) extends StatefulModule[Variable, Variable, Option[Variable]] {
  val inputSize = weightXh.shape.last
  val hiddenSize = biasH.shape.last

  override def state: Seq[(Variable, PTag)] =
    List(
      (weightXh, RNN.WeightXh),
      (weightHh, RNN.WeightHh),
      (biasH, RNN.BiasH)
    )

  private def initHidden(batchSize: Long) = {
    param(ATen.zeros(Array(batchSize, hiddenSize), weightHh.options))(
      weightHh.pool
    ).releasable
  }

  def forward(a: (Variable, Option[Variable])) = forward1(a._1, a._2)
  def forward1(x: Variable, state: Option[Variable]) = {
    val timesteps = x.shape.head
    val batchSize = x.shape(1)
    val outputs = mutable.ArrayBuffer[Variable]()
    val init = state.getOrElse(initHidden(batchSize))
    val lastHidden =
      (0 until timesteps.toInt).foldLeft(init) { (h, t) =>
        val xt = x.select(0, t)
        val newHidden = (xt.mm(weightXh) + h.mm(weightHh) + biasH).tanh

        outputs.append(newHidden)
        newHidden
      }
    (ConcatenateAddNewDim(outputs).value, Some(lastHidden))

  }

}

object RNN {
  implicit val trainingMode = TrainingMode.identity[RNN]
  implicit val is = InitState.make[RNN, Option[Variable]](_ => None)
  implicit val load = Load.make[RNN] { m => tensors =>
    implicit val pool = m.weightHh.pool
    m.copy(
      weightXh = param(tensors(0)),
      weightHh = param(tensors(1)),
      biasH = param(tensors(2))
    )
  }
  case object WeightXh extends LeafTag
  case object WeightHh extends LeafTag
  case object BiasH extends LeafTag

  def apply(
      in: Int,
      hiddenSize: Int,
      tOpt: TensorOptions
  )(implicit pool: AllocatedVariablePool): RNN =
    RNN(
      weightXh = param(
        ATen.normal_3(
          0d,
          math.sqrt(2d / (in + hiddenSize)),
          Array(in, hiddenSize),
          tOpt
        )
      ),
      weightHh = param(
        ATen.normal_3(
          0d,
          math.sqrt(2d / (hiddenSize + hiddenSize)),
          Array(hiddenSize, hiddenSize),
          tOpt
        )
      ),
      biasH = param(
        ATen.zeros(
          Array(1, hiddenSize),
          tOpt
        )
      )
    )

}