package lamp.nn

import lamp.autograd.{Variable, param, Conv1D => Conv1dOp, const}
import aten.{ATen, TensorOptions}
import lamp.autograd.TensorHelpers
import aten.Tensor

case class Conv1D(
    weights: Variable,
    bias: Variable,
    stride: Long,
    padding: Long,
    dilation: Long,
    groups: Long
) extends Module {

  override def load(parameters: Seq[Tensor]) = {
    val w = param(parameters.head)
    val b = param(parameters(1))
    copy(weights = w, bias = b)
  }

  override val state = List(
    weights -> Conv1D.Weights,
    bias -> Conv1D.Bias
  )

  def forward(x: Variable): Variable =
    Conv1dOp(x, weights, bias, stride, padding, dilation, groups).value

}

object Conv1D {
  case object Weights extends LeafTag
  case object Bias extends LeafTag
  def apply(
      inChannels: Long,
      outChannels: Long,
      kernelSize: Long,
      tOpt: TensorOptions,
      bias: Boolean = true,
      stride: Long = 1,
      padding: Long = 0,
      dilation: Long = 1,
      groups: Long = 1
  ): Conv1D = {
    val weightVar = param(
      ATen.normal_3(
        0d,
        math.sqrt(2d / (outChannels + inChannels)),
        Array(outChannels, inChannels, kernelSize),
        tOpt
      )
    )
    val biasVar = {
      val t = ATen.zeros(Array(outChannels), tOpt)
      if (bias) param(t) else const(t)
    }
    Conv1D(
      weightVar,
      biasVar,
      stride,
      padding,
      dilation,
      groups
    )

  }

}
