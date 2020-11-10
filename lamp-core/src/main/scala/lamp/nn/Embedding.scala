package lamp.nn

import lamp.autograd.{Variable, param}
import aten.{ATen, TensorOptions}
import lamp.Sc
import lamp.scope

/**
  * Learnable mapping from classes to dense vectors.
  * Equivalent to L * W where
  *   L is the n x C one-hot encoded matrix of the classes
  *   * is matrix multiplication
  *   W is the C x dim dense matrix.
  * W is learnable.
  * L is never computed directly.
  * C is the number of classes.
  * n is the size of the batch.
  *
  * Input is a long tensor with values in [0,C-1].
  */
case class Embedding(weights: Variable) extends Module {
  override val state = List(
    weights -> Embedding.Weights
  )

  def forward[S: Sc](x: Variable): Variable =
    lamp.autograd.Embedding(scope, x, weights).value

}

object Embedding {
  implicit val trainingMode = TrainingMode.identity[Embedding]
  implicit val load = Load.make[Embedding] { m => parameters =>
    implicit val pool = m.weights.pool
    val w = param(parameters.head)
    m.copy(weights = w)
  }
  case object Weights extends LeafTag
  def apply[S: Sc](
      classes: Int,
      dimensions: Int,
      tOpt: TensorOptions
  ): Embedding =
    Embedding(
      weights = param(
        ATen.normal_3(
          0d,
          math.sqrt(2d / (classes + dimensions)),
          Array(classes, dimensions),
          tOpt
        )
      )
    )
}
