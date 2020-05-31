package lamp.nn

import aten.Tensor
import aten.ATen
import cats.implicits._

// https://arxiv.org/pdf/1711.05101.pdf algorithm 1
case class SGDW(
    parameters: Seq[(Tensor, PTag)],
    learningRate: OptimizerHyperparameter,
    weightDecay: OptimizerHyperparameter,
    momentum: Option[OptimizerHyperparameter] = None,
    scheduler: Long => Double = _ => 1d
) extends Optimizer {
  val velocity: List[Option[(Tensor, OptimizerHyperparameter)]] = momentum.map {
    m =>
      parameters.toList.map {
        case (param, _) => (ATen.zeros_like(param, param.options), m)
      }
  }.sequence

  var stepCount = 0L

  def step(gradients: Seq[Tensor]) = {
    stepCount += 1L
    val scheduleFactor = scheduler(stepCount)
    parameters.zip(gradients).zip(velocity).foreach {
      case (((param, tag), gradients), None) =>
        val wd = weightDecay(tag)
        if (wd != 0d) {
          ATen.add_out(
            param,
            param,
            param,
            (-1) * wd * scheduleFactor
          )
        }

        ATen.add_out(
          param,
          param,
          gradients,
          (-1) * learningRate(tag) * scheduleFactor
        )

      case (((param, tag), gradients), Some((velocity, momentum))) =>
        val m = momentum(tag)

        velocity.mul_(m)

        ATen.add_out(
          velocity,
          velocity,
          gradients,
          learningRate(tag) * scheduleFactor
        )

        val wd = weightDecay(tag)
        if (wd != 0d) {
          ATen.add_out(
            param,
            param,
            param,
            -1 * wd * scheduleFactor
          )
        }
        ATen.add_out(
          param,
          param,
          velocity,
          -1
        )
    }

  }
}
