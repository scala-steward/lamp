package lamp.experiment.recursivelm.model

import lamp._
import lamp.nn._
import lamp.autograd.const
import cats.effect._
import lamp.data._
import BatchStream.scopeInResource

object Sampling {
  def autoregressiveInference(
      model: LanguageModelModule,
      modelBlockSize: Int,
      prefix: Array[Char],
      length: Int,
      temperature: Double
  )(scope: Scope): IO[Array[Char]] = {
    assert(temperature > 0d)
    val device = model.tokenEmbedding.weights.value.device
    def makeInput(memory: Option[STen], prefix: Array[Char])(implicit
        scope: Scope
    ) = {
      val tokens =
        STen
          .fromLongArray(
            prefix.map(_.toLong)
          )
          .unsqueeze(0)

      val positions =
        STen.fromLongArray(Array(tokens.shape(1) - 1)).unsqueeze(0)

      LanguageModelInput(
        tokens = const(device.to(tokens)),
        memory = memory.map(const),
        positions = Some(device.to(positions))
      )
    }

    def makeBatch(memory: Option[STen], prefix: Array[Char]) =
      BatchStream.single(scopeInResource.map { implicit scope =>
        NonEmptyBatch(makeInput(memory, prefix))
      })

    def single(
        memory: Option[STen],
        prefix: Array[Char]
    )(implicit scope: Scope): IO[LanguageModelOutputNonVariable] =
      IOLoops
        .runBatchStream(
          makeBatch(memory, prefix),
          buffers = Resource.unit,
          model = lamp.nn.sequence(
            model,
            GenericFun[LanguageModelOutput, LanguageModelOutputNonVariable](_ =>
              _.toSTen
            )
          )
        )
        .map { v =>
          v.head
        }

    def loop(n: Int, acc: Array[Char], memory: Option[STen])(
        scope: Scope
    ): IO[Array[Char]] =
      if (n == 0) IO.pure(acc)
      else
        Scope
          .bracket(scope) { implicit scope =>
            val prefix = acc.takeRight(modelBlockSize)
            single(memory, prefix).map { output =>
              val probs = (output.languageModelLogits / temperature)
                .logSoftMax(2)
                .exp
                .view(1, -1)

              val sample = STen.multinomial(
                probs,
                1,
                false
              )
              assert(sample.numel == 1)
              val next = sample.toLongArray.head.toChar
              val memory = output.memory
              (next, memory)
            }
          }
          .flatMap { case (next, memory) =>
            loop(n - 1, acc :+ next, Some(memory))(scope)
          }

    loop(length, prefix, None)(scope)

  }
}