package lamp
import scala.language.implicitConversions
import lamp.autograd.Variable
import aten.Tensor
import aten.ATen
import cats.effect.IO

package object nn {
  type Module = GenericModule[Variable, Variable]
  type StatefulModule[A, B, C] = GenericModule[(A, C), (B, C)]
  implicit def funToModule(fun: Variable => Variable) = Fun(fun)

  implicit class TrainingModeSyntax[M: TrainingMode](m: M) {
    def asEval: M = implicitly[TrainingMode[M]].asEval(m)
    def asTraining: M = implicitly[TrainingMode[M]].asTraining(m)
  }
  implicit class LoadSyntax[M: Load](m: M) {
    def load(tensors: Seq[Tensor]): M =
      implicitly[Load[M]].load(m, tensors)
  }
  implicit class InitStateSyntax[M, C](m: M)(implicit is: InitState[M, C]) {
    def initState = is.initState(m)
  }

  implicit class ToLift[M <: Module](mod: M with Module) {
    def lift = LiftedModule(mod)
  }
  implicit class ToUnlift[A, B, C, M <: StatefulModule[A, B, C]](
      mod: M with StatefulModule[A, B, C]
  )(
      implicit is: InitState[M, C]
  ) {
    def unlift = UnliftedModule[A, B, C, M](mod)(is)
  }

  def gradientClippingInPlace(
      gradients: Seq[Option[Tensor]],
      theta: Double
  ): Unit = {
    val norm = math.sqrt(gradients.map {
      case Some(g) =>
        val tmp = ATen.pow_0(g, 2d)
        val d = ATen.sum_0(tmp).toMat.raw(0)
        tmp.release
        d
      case None => 0d
    }.sum)
    if (norm > theta) {
      gradients.foreach {
        case None =>
        case Some(g) =>
          g.scalar(theta / norm)
            .use { scalar => IO { ATen.mul_out(g, g, scalar) } }
            .unsafeRunSync()
      }
    }
  }
}
