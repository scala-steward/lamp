package lamp.nn

import lamp.autograd._
import lamp.Sc
import lamp.Scope
import lamp.scope
import lamp.STen
import lamp.Movable
import lamp.EmptyMovable

case class Recursive[A, M <: GenericModule[A, A]](
    member: M with GenericModule[A, A],
    n: Int
) extends GenericModule[A, A] {
  override def state = member.state

  def forward[S: Sc](x: A) =
    (0 until n).foldLeft(x)((x, _) => member.forward(x))

}
object Recursive {

  implicit def trainingMode[A, M <: GenericModule[A, A]: TrainingMode]
      : TrainingMode[Recursive[A, M]] =
    TrainingMode.make[Recursive[A, M]](
      module => Recursive(module.member.asEval, module.n),
      module => Recursive(module.member.asTraining, module.n)
    )

  implicit def load[A, M <: GenericModule[A, A]: Load]: Load[Recursive[A, M]] =
    Load.compose(_.member)

}

case class EitherModule[
    A,
    B,
    M1 <: GenericModule[A, B],
    M2 <: GenericModule[A, B]
](
    members: Either[M1 with GenericModule[A, B], M2 with GenericModule[A, B]]
) extends GenericModule[A, B] {
  override def state =
    members.fold(_.state, _.state)
  def forward[S: Sc](x: A) =
    members.fold(_.forward(x), _.forward(x))
}
object EitherModule {

  implicit def trainingMode[
      A,
      B,
      M1 <: GenericModule[A, B]: TrainingMode,
      M2 <: GenericModule[A, B]: TrainingMode
  ]: TrainingMode[EitherModule[A, B, M1, M2]] =
    TrainingMode.make[EitherModule[A, B, M1, M2]](
      module => EitherModule(module.members.left.map(_.asEval).map(_.asEval)),
      module =>
        EitherModule(
          module.members.left.map(_.asTraining).map(_.asTraining)
        )
    )

  implicit def load[
      A,
      B,
      M1 <: GenericModule[A, B]: Load,
      M2 <: GenericModule[A, B]: Load
  ]: Load[EitherModule[A, B, M1, M2]] =
    Load.make[EitherModule[A, B, M1, M2]] { module => tensors =>
      module.members.fold(_.load(tensors), _.load(tensors))
    }

  case class Tag[T <: PTag](t: T, idx: Int) extends PTag {
    def leaf = t
  }
}

case class Sequential[A, M <: GenericModule[A, A]](
    members: M with GenericModule[A, A]*
) extends GenericModule[A, A] {
  override def state =
    members.zipWithIndex.flatMap { case (member, idx) =>
      member.state.map { case (param, ptag) =>
        (param, Sequential.Tag(ptag, idx))
      }
    }
  def forward[S: Sc](x: A) =
    members.foldLeft(x) { case (x, m) =>
      m.forward(x)
    }

}
object Sequential {

  implicit def trainingMode[A, M <: GenericModule[A, A]: TrainingMode]
      : TrainingMode[Sequential[A, M]] =
    TrainingMode.make[Sequential[A, M]](
      module => Sequential(module.members.map(_.asEval): _*),
      module => Sequential(module.members.map(_.asTraining): _*)
    )

  implicit def load[A, M <: GenericModule[A, A]: Load]: Load[Sequential[A, M]] =
    Load.make[Sequential[A, M]] { module => tensors =>
      module.members.foldLeft((List[Unit](), tensors)) {
        case ((acc, params), member) =>
          val numParam = member.state.size
          val loaded = member.load(params.take(numParam))
          (acc.:+(loaded), params.drop(numParam))

      }
      ()
    }

  case class Tag[T <: PTag](t: T, idx: Int) extends PTag {
    def leaf = t
  }
}

case class Fun(fun: Scope => Variable => Variable) extends Module {
  def state = Nil
  def forward[S: Sc](x: Variable): Variable = fun(scope)(x)
}
object Fun {
  implicit val trainingMode: TrainingMode[Fun] = TrainingMode.identity[Fun]
  implicit val load: Load[Fun] = Load.identity[Fun]
}
case class Debug(fun: (STen, Boolean, Boolean) => Unit) extends Module {
  def state = Nil
  def forward[S: Sc](x: Variable): Variable = x.debug(fun)
}
object Debug {
  implicit val trainingMode: TrainingMode[Debug] = TrainingMode.identity[Debug]
  implicit val load: Load[Debug] = Load.identity[Debug]
}

case class GenericFun[A, B](fun: Scope => A => B) extends GenericModule[A, B] {
  def state = Nil
  def forward[S: Sc](x: A): B = fun(scope)(x)
}
object GenericFun {
  implicit def trainingMode[A, B]: TrainingMode[GenericFun[A, B]] =
    TrainingMode.identity[GenericFun[A, B]]
  implicit def load[A, B]: Load[GenericFun[A, B]] =
    Load.identity[GenericFun[A, B]]
}

case class WrapFun[A, B, M <: GenericModule[A, B], O](
    module: M,
    fun: (A, B) => O
) extends GenericModule[A, (B, O)] {
  def state = module.state
  def forward[S: Sc](a: A): (B, O) = {
    val b = module.forward(a)
    val o = fun(a, b)
    (b, o)
  }
}
object WrapFun {
  implicit def trainingMode[A, B, O, M <: GenericModule[A, B]: TrainingMode]
      : TrainingMode[WrapFun[A, B, M, O]] =
    TrainingMode.make[WrapFun[A, B, M, O]](
      module => WrapFun(module.module.asEval, module.fun),
      module => WrapFun(module.module.asTraining, module.fun)
    )

  implicit def load[A, B, O, M <: GenericModule[A, B]: Load]
      : Load[WrapFun[A, B, M, O]] =
    Load.compose(_.module)
}
case class LiftedModule[M <: Module](mod: M with Module)
    extends StatefulModule[Variable, Variable, Unit] {
  def state = mod.state
  def forward[S: Sc](x: (Variable, Unit)) = (mod.forward(x._1), ())
}
object LiftedModule {
  implicit def trainingMode[
      M <: Module: TrainingMode
  ]: TrainingMode[LiftedModule[M]] =
    TrainingMode.make[LiftedModule[M]](
      m => m.copy(mod = m.mod.asEval),
      m => m.copy(mod = m.mod.asTraining)
    )
  implicit def load[
      M <: Module: Load
  ]: Load[LiftedModule[M]] =
    Load.make[LiftedModule[M]](m => tensors => m.mod.load(tensors))
  implicit def initState[M <: Module]: InitState[LiftedModule[M], Unit] =
    InitState.make[LiftedModule[M], Unit](_ => ())
}

case class UnliftedModule[A, B, C, D, M <: StatefulModule2[A, B, C, D]](
    statefulModule: M with StatefulModule2[A, B, C, D]
)(implicit init: InitState[M, C])
    extends GenericModule[A, B] {
  def state = statefulModule.state
  def forward[S: Sc](x: A) =
    statefulModule.forward((x, statefulModule.initState))._1
}
object UnliftedModule {
  implicit def trainingMode[A, B, C, D, M <: StatefulModule2[A, B, C, D]](
      implicit
      t: TrainingMode[M],
      is: InitState[M, C]
  ): TrainingMode[UnliftedModule[A, B, C, D, M]] =
    TrainingMode.make[UnliftedModule[A, B, C, D, M]](
      m => UnliftedModule[A, B, C, D, M](m.statefulModule.asEval),
      m => UnliftedModule[A, B, C, D, M](m.statefulModule.asTraining)
    )
  implicit def load[A, B, C, D, M <: StatefulModule2[A, B, C, D]: Load]
      : Load[UnliftedModule[A, B, C, D, M]] =
    Load.make[UnliftedModule[A, B, C, D, M]](m =>
      tensors => m.statefulModule.load(tensors)
    )
  implicit def initState[A, B, C, D, M <: StatefulModule2[A, B, C, D]](implicit
      is: InitState[M, C]
  ): InitState[UnliftedModule[A, B, C, D, M], Unit] =
    InitState.make[UnliftedModule[A, B, C, D, M], Unit](m =>
      is.initState(m.statefulModule)
    )
}

object GenericModule {
  implicit def movable[A, B]: Movable[GenericModule[A, B]] =
    Movable.nonEmpty[GenericModule[A, B]] { m =>
      m.state
        .flatMap(_._1 match {
          case ConstantWithGrad(value, pd) => List(value.value, pd.value)
          case ConstantWithoutGrad(value)  => List(value.value)
        })
        .toList
    }
}

/** Base type of modules
  *
  * Modules are functions of type `(Seq[lamp.autograd.Constant],A) => B`, where
  * the `Seq[lamp.autograd.Constant]` arguments are optimizable parameters and
  * `A` is a non-optimizable input.
  *
  * Modules provide a way to build composite functions while also keep track of
  * the parameter list of the composite function.
  *
  * ===Example===
  * {{{
  * case object Weights extends LeafTag
  * case object Bias extends LeafTag
  * case class Linear(weights: Constant, bias: Option[Constant]) extends Module {
  *
  *   override val state = List(
  *     weights -> Weights
  *   ) ++ bias.toList.map(b => (b, Bias))
  *
  *   def forward[S: Sc](x: Variable): Variable = {
  *     val v = x.mm(weights)
  *     bias.map(_ + v).getOrElse(v)
  *
  *   }
  * }
  * }}}
  *
  * Some other attributes of modules are attached by type classes e.g. with the
  * [[nn.TrainingMode]], [[nn.Load]] type classes.
  *
  * @tparam A
  *   the argument type of the module
  * @tparam B
  *   the value type of the module
  * @see
  *   [[nn.Module]] is an alias for simple `Variable => Variable` modules
  */
trait GenericModule[A, B] {

  /** The implementation of the function.
    *
    * In addition of `x` it can also use all the `state to compute its value.
    */
  def forward[S: Sc](x: A): B

  /** Alias of forward */
  def apply[S: Sc](a: A): B = forward(a)

  /** List of optimizable, or non-optimizable, but stateful parameters
    *
    * Stateful means that the state is carried over the repeated forward calls.
    */
  def state: Seq[(Constant, PTag)]

  /** Returns the state variables which need gradient computation. */
  final def parameters =
    state.filter(v => v._1.needsGrad)

  final def zeroGrad() = {
    parameters.foreach { case (param, _) =>
      param.zeroGrad()
    }
  }

  /** Computes the gradient of loss with respect to the parameters. */
  final def gradients(
      loss: Variable,
      zeroGrad: Boolean = true
  ): Seq[Option[STen]] = {
    if (zeroGrad) {
      parameters.foreach { case (param, _) =>
        param.zeroGrad()
      }
    }
    loss.backprop()
    val g = parameters.map { case (param, _) =>
      param.partialDerivative
    }
    g
  }

  /** Returns the total number of optimizable parameters. */
  final def learnableParameters =
    parameters.filter(_._1.needsGrad).map(_._1.value.numel).sum
}

/** A small trait to mark paramters for unique identification */
trait PTag {
  def leaf: PTag
}
object PTag {
  implicit val isMovable: EmptyMovable[PTag] = Movable.empty[PTag]
}
trait LeafTag extends PTag {
  def leaf: PTag = this
}
case object NoTag extends LeafTag

/** Type class about how to switch a module into training or evaluation mode */
trait TrainingMode[M] {
  def asEval(m: M): M
  def asTraining(m: M): M

}

object TrainingMode {
  def make[M](asEval1: M => M, asTraining1: M => M) = new TrainingMode[M] {
    def asEval(m: M) = asEval1(m)
    def asTraining(m: M) = asTraining1(m)
  }
  def identity[M] =
    TrainingMode.make(scala.Predef.identity[M], scala.Predef.identity[M])
}

/** Type class about how to load the contents of the state of modules from
  * external tensors
  */
trait Load[M] {
  def load(m: M, tensors: Seq[STen]): Unit
}
object Load {
  def identity[M]: Load[M] = Load.make[M](_ => _ => ())
  def make[M](f: M => Seq[STen] => Unit) = new Load[M] {
    def load(m: M, tensors: Seq[STen]): Unit = f(m)(tensors)
  }
  def compose[M, T1 <: GenericModule[_, _]: Load](f1: M => T1) = Load.make[M] {
    m => tensors =>
      f1(m).load(tensors)
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2
  ) = Load.make[M] { m => tensors =>
    loadMultiple(f1(m), f2(m), tensors)
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3
  ) = Load.make[M] { m => tensors =>
    loadMultiple(f1(m), f2(m), f3(m), tensors)
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4
  ) = Load.make[M] { m => tensors =>
    loadMultiple(f1(m), f2(m), f3(m), f4(m), tensors)
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load,
      T5 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4,
      f5: M => T5
  ) = Load.make[M] { m => tensors =>
    loadMultiple(f1(m), f2(m), f3(m), f4(m), f5(m), tensors)
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load,
      T5 <: GenericModule[_, _]: Load,
      T6 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4,
      f5: M => T5,
      f6: M => T6
  ) = Load.make[M] { m => tensors =>
    loadMultiple(f1(m), f2(m), f3(m), f4(m), f5(m), f6(m), tensors)
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load,
      T5 <: GenericModule[_, _]: Load,
      T6 <: GenericModule[_, _]: Load,
      T7 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4,
      f5: M => T5,
      f6: M => T6,
      f7: M => T7
  ) = Load.make[M] { m => tensors =>
    loadMultiple(f1(m), f2(m), f3(m), f4(m), f5(m), f6(m), f7(m), tensors)
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load,
      T5 <: GenericModule[_, _]: Load,
      T6 <: GenericModule[_, _]: Load,
      T7 <: GenericModule[_, _]: Load,
      T8 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4,
      f5: M => T5,
      f6: M => T6,
      f7: M => T7,
      f8: M => T8
  ) = Load.make[M] { m => tensors =>
    loadMultiple(
      f1(m),
      f2(m),
      f3(m),
      f4(m),
      f5(m),
      f6(m),
      f7(m),
      f8(m),
      tensors
    )
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load,
      T5 <: GenericModule[_, _]: Load,
      T6 <: GenericModule[_, _]: Load,
      T7 <: GenericModule[_, _]: Load,
      T8 <: GenericModule[_, _]: Load,
      T9 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4,
      f5: M => T5,
      f6: M => T6,
      f7: M => T7,
      f8: M => T8,
      f9: M => T9
  ) = Load.make[M] { m => tensors =>
    loadMultiple(
      f1(m),
      f2(m),
      f3(m),
      f4(m),
      f5(m),
      f6(m),
      f7(m),
      f8(m),
      f9(m),
      tensors
    )
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load,
      T5 <: GenericModule[_, _]: Load,
      T6 <: GenericModule[_, _]: Load,
      T7 <: GenericModule[_, _]: Load,
      T8 <: GenericModule[_, _]: Load,
      T9 <: GenericModule[_, _]: Load,
      T10 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4,
      f5: M => T5,
      f6: M => T6,
      f7: M => T7,
      f8: M => T8,
      f9: M => T9,
      f10: M => T10
  ) = Load.make[M] { m => tensors =>
    loadMultiple(
      f1(m),
      f2(m),
      f3(m),
      f4(m),
      f5(m),
      f6(m),
      f7(m),
      f8(m),
      f9(m),
      f10(m),
      tensors
    )
  }
  def compose[
      M,
      T1 <: GenericModule[_, _]: Load,
      T2 <: GenericModule[_, _]: Load,
      T3 <: GenericModule[_, _]: Load,
      T4 <: GenericModule[_, _]: Load,
      T5 <: GenericModule[_, _]: Load,
      T6 <: GenericModule[_, _]: Load,
      T7 <: GenericModule[_, _]: Load,
      T8 <: GenericModule[_, _]: Load,
      T9 <: GenericModule[_, _]: Load,
      T10 <: GenericModule[_, _]: Load,
      T11 <: GenericModule[_, _]: Load
  ](
      f1: M => T1,
      f2: M => T2,
      f3: M => T3,
      f4: M => T4,
      f5: M => T5,
      f6: M => T6,
      f7: M => T7,
      f8: M => T8,
      f9: M => T9,
      f10: M => T10,
      f11: M => T11
  ) = Load.make[M] { m => tensors =>
    loadMultiple(
      f1(m),
      f2(m),
      f3(m),
      f4(m),
      f5(m),
      f6(m),
      f7(m),
      f8(m),
      f9(m),
      f10(m),
      f11(m),
      tensors
    )
  }
}

/** Type class about how to initialize recurrent neural networks */
trait InitState[M, C] {
  def initState(m: M): C
}
object InitState {
  def make[M, C](f: M => C) = new InitState[M, C] {
    def initState(m: M) = f(m)
  }
}

case class MappedState[A, B, C, D, M <: StatefulModule[A, B, C]](
    statefulModule: M with StatefulModule[A, B, C],
    map: C => D
) extends StatefulModule2[A, B, C, D] {
  def state = statefulModule.state
  def forward[S: Sc](x: (A, C)) = {
    val (b, c) = statefulModule.forward(x)
    (b, map(c))
  }
}
object MappedState {
  implicit def trainingMode[A, B, C, D, M <: StatefulModule[A, B, C]](implicit
      t: TrainingMode[M]
  ): TrainingMode[MappedState[A, B, C, D, M]] =
    TrainingMode.make[MappedState[A, B, C, D, M]](
      m => MappedState[A, B, C, D, M](m.statefulModule.asEval, m.map),
      m => MappedState[A, B, C, D, M](m.statefulModule.asTraining, m.map)
    )
  implicit def load[A, B, C, D, M <: StatefulModule[A, B, C]: Load]
      : Load[MappedState[A, B, C, D, M]] =
    Load.make[MappedState[A, B, C, D, M]](m =>
      tensors => m.statefulModule.load(tensors)
    )
  implicit def initState[A, B, C, D, M <: StatefulModule[A, B, C]](implicit
      is: InitState[M, C]
  ): InitState[MappedState[A, B, C, D, M], C] =
    InitState.make[MappedState[A, B, C, D, M], C](m =>
      is.initState(m.statefulModule)
    )
}
