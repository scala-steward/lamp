package lamp.nn

import aten.Tensor
import aten.ATen

trait Optimizer {
  def step(gradients: Seq[Tensor]): Unit
}

trait OptimizerHyperparameter { def apply(ptag: PTag): Double }
case class simple(v: Double) extends OptimizerHyperparameter {
  def apply(p: PTag) = v
}

case class DependentHyperparameter(default: Double)(
    pf: PartialFunction[PTag, Double]
) extends OptimizerHyperparameter {
  def apply(p: PTag) = pf.applyOrElse(p, (_: PTag) => default)
}