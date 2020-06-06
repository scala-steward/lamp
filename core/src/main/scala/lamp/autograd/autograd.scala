package lamp.autograd
import org.saddle._
import org.saddle.ops.BinOps._
import org.saddle.linalg._
import aten.{Tensor, ATen}
import java.{util => ju}
import aten.TensorOptions

/**
  * Params: the input and the function which calculates the partial derivative
  * of the function value wrt to this input
  *
  * y = f1 o f2 o .. o fn
  *
  * One of these subexpression (f_i) has value w2 and arguments w1.
  * We can write this: dy/dw1 = dy/dw2 * dw2/dw1.
  * dw2/dw1 is the Jacobian of f_i at the current value of w1.
  * dy/dw2 is the Jacobian of y wrt to w2 at the current value of w2.
  *
  * The current value of w1 and w2 are computed in a forward pass.
  * The value dy/dy is 1 and from this dy/dw2 is recursed in the backward pass.
  * The Jacobian function of dw2/dw1 is either computed symbolically.
  *
  * https://en.wikipedia.org/wiki/Automatic_differentiation#Reverse_accumulation
  * http://www.cs.cmu.edu/~wcohen/10-605/notes/autodiff.pdf
  *
  * The function given in this argument is dy/dw2 => dy/dw2 * dw2/dw1.
  * The argument is coming down from the backward pass.
  * The Op fills in the symbolic part and the multiplication.
  *
  * The shape of the argument given to that function is the shape of the value of Op (dy/dw2)
  * The shape of the return is the shape of the argument (parameter) with respect the
  * derivative is taken (dy/dw1)
  *
  */
trait Op {
  val value: Variable
  val params: List[(Variable, (Tensor, Tensor) => Unit)]

}

// Variable takes ownership of the value: Tensor
// therefore it must be the sole owner
case class Variable(
    op: Op,
    value: Tensor,
    needsGrad: Boolean = true,
    leaf: Boolean = false
) {

  def options = value.options

  var partialDerivative: Option[Tensor] = None

  val sizes = value.sizes.toList

  def shape = sizes

  val id = ju.UUID.randomUUID()

  def releaseAll(): Unit = {
    wengert.filterNot(_.leaf).foreach { variable =>
      variable.value.release
      variable.partialDerivative.foreach(_.release)
    }
  }
  def detached = copy(needsGrad = false)
  def zeroGrad() = {
    partialDerivative.foreach { t => ATen.zero_(t) }
  }

  lazy val wengert = Autograd.topologicalSort(this)

  def backprop(): Unit = {
    if (partialDerivative.isEmpty) {
      partialDerivative = Some(
        ATen.ones_like(value, value.options)
      )
    }
    wengert.foreach { v =>
      v.op.params.foreach {
        case (v1, computeGrad) =>
          v1.accumulateGrad(v.partialDerivative.get, computeGrad)

      }
    }

  }

  def zipBackward(fn: (Tensor, Tensor) => Unit) = (this, fn)

  def accumulateGrad(
      incoming: Tensor,
      computeGrad: (Tensor, Tensor) => Unit
  ) = if (needsGrad) {

    if (partialDerivative.isEmpty) {
      partialDerivative = Some(
        ATen.zeros(value.sizes, value.options())
      )
    }
    computeGrad(incoming, partialDerivative.get)
  }

  def stringify(printValue: Boolean = false) =
    if (printValue)
      s"$op == $value"
    else s"$op"

  def t = Transpose(this).value
  def +(other: Variable) = Add(this, other).value
  def -(other: Variable) = Minus(this, other).value
  def *(other: Variable) = Mult(this, other).value
  def /(other: Variable) = Div(this, other).value
  def mm(other: Variable) = MatMul(this, other).value
  def relu = Relu(this).value
  def gelu = Gelu(this).value
  def dropout(prob: Double, train: Boolean) = Dropout(this, prob, train).value
  def sum = Sum(this).value
  def rowSum = RowSum(this).value
  def colSum = ColSum(this).value
  def exp = Exp(this).value
  def log = Log(this).value
  def sin = Sin(this).value
  def cos = Cos(this).value
  def tan = Tan(this).value
  def tanh = Tanh(this).value
  def atan = ArcTan(this).value
  def pow(const: Double) = PowConst(this, const).value
  def logSoftMax = LogSoftMaxRowWise(this).value
  def crossEntropy(other: Variable) = (const(-1)) * ((this.*(other)).rowSum)
  def nllLoss(target: Tensor, numClasses: Int, reduction: Reduction = Mean) =
    NllLoss(this, target, numClasses, reduction).value
  def squaredFrobenius = SquaredFrobeniusMatrixNorm(this).value
  def mean(dim: List[Int]) = Mean(this, dim).value

  def toMat = TensorHelpers.toMat(value)
  def toLongMat = TensorHelpers.toMatLong(value)
}

object Autograd {

  private[autograd] def topologicalSort[D](root: Variable): Seq[Variable] = {
    type V = Variable
    var order = List.empty[V]
    var marks = Set.empty[ju.UUID]
    var currentParents = Set.empty[ju.UUID]

    def visit(n: V): Unit =
      if (marks.contains(n.id)) ()
      else {
        if (currentParents.contains(n.id)) {
          println(s"error: loop to ${n.id}")
          ()
        } else {
          currentParents = currentParents + n.id
          val children = n.op.params.map(_._1)
          children.foreach(visit)
          currentParents = currentParents - n.id
          marks = marks + n.id
          order = n :: order
        }
      }

    visit(root)

    order

  }

}
