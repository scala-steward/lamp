package lamp.data

import org.scalatest.funsuite.AnyFunSuite
import lamp.CPU
import lamp.SinglePrecision
import lamp.nn.Embedding
import lamp.nn.RNN
import lamp.nn.Fun
import lamp.nn.SupervisedModel
import lamp.nn.LossFunctions
import lamp.nn.AdamW
import lamp.nn.simple
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import scala.io.Codec
import lamp.nn.SlowTest
import lamp.nn.LSTM
import lamp.nn.SeqLinear
import lamp.nn.statefulSequence
import lamp.Scope
import lamp.STen
import cats.effect.unsafe.implicits.global
import lamp.nn.ToLift
import lamp.nn.ToUnlift

class TextGenerationSuite extends AnyFunSuite {
  val asciiSilentCharsetDecoder = Charset
    .forName("UTF8")
    .newDecoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.REPLACE)
  test("text learning - slow - LSTM", SlowTest) {
    Scope.root { implicit scope =>
      val trainText =
        scala.io.Source
          .fromInputStream(getClass.getResourceAsStream("/35-0.txt"))(
            Codec.apply(asciiSilentCharsetDecoder)
          )
          .mkString

      val (vocab, _) = Text.charsToIntegers(trainText)
      val trainTokenized = Text.charsToIntegers(trainText, vocab)
      val vocabularSize = vocab.size
      val lookAhead = 5
      val device = CPU
      val precision = SinglePrecision
      val tensorOptions = device.options(precision)
      val model = {
        val classWeights =
          STen.ones(List(vocabularSize), tensorOptions)
        val net =
          statefulSequence(
            Embedding(
              classes = vocabularSize,
              dimensions = 10,
              tOpt = tensorOptions
            ).lift,
            LSTM(
              in = 10,
              hiddenSize = 256,
              tOpt = tensorOptions
            ),
            Fun(implicit scope => _.relu).lift,
            SeqLinear(in = 256, out = vocabularSize, tOpt = tensorOptions).lift,
            Fun(implicit scope => _.logSoftMax(2)).lift
          ).unlift

        SupervisedModel(
          net,
          LossFunctions.SequenceNLL(vocabularSize, classWeights)
        )
      }
      val rng = new scala.util.Random()
      val trainEpochs = (_: IOLoops.TrainingLoopContext) =>
        Text
          .minibatchesFromText(
            trainTokenized,
            64,
            lookAhead,
            rng
          )

      val optimizer = AdamW.factory(
        weightDecay = simple(0.00),
        learningRate = simple(0.1),
        clip = Some(1d)
      )

      val (_, _, learningCurve, _, _) = IOLoops
        .epochs(
          model = model,
          optimizerFactory = optimizer,
          trainBatchesOverEpoch = trainEpochs,
          validationBatchesOverEpoch = None,
          epochs = 15
        )
        .unsafeRunSync()

      assert(learningCurve.last._2 < 3d)
      ()
    }
  }
  test("text learning", SlowTest) {
    Scope.root { implicit scope =>
      val trainText =
        scala.io.Source
          .fromInputStream(getClass.getResourceAsStream("/35-0.txt"))(
            Codec.apply(asciiSilentCharsetDecoder)
          )
          .mkString

      val (vocab, _) = Text.charsToIntegers(trainText)
      val rvocab = vocab.map(_.swap)

      val trainTokenized = Text.charsToIntegers(trainText, vocab)
      val vocabularSize = vocab.size

      val hiddenSize = 1024
      val lookAhead = 10
      val device = CPU
      val precision = SinglePrecision
      val tensorOptions = device.options(precision)
      val model = {
        val classWeights =
          STen.ones(List(vocabularSize), tensorOptions)
        val net =
          statefulSequence(
            Embedding(
              classes = vocabularSize,
              dimensions = 10,
              tOpt = tensorOptions
            ).lift,
            RNN(
              in = 10,
              hiddenSize = hiddenSize,
              tOpt = tensorOptions
            ),
            Fun(implicit scope => _.relu).lift,
            SeqLinear(
              in = hiddenSize,
              out = vocabularSize,
              tOpt = tensorOptions
            ).lift,
            Fun(implicit scope => _.logSoftMax(2)).lift
          ).unlift

        SupervisedModel(
          net,
          LossFunctions.SequenceNLL(vocabularSize, classWeights)
        )
      }
      val rng = new scala.util.Random
      val trainEpochs = (_: IOLoops.TrainingLoopContext) =>
        Text
          .minibatchesFromText(
            trainTokenized,
            64,
            lookAhead,
            rng
          )

      val optimizer = AdamW.factory(
        weightDecay = simple(0.00),
        learningRate = simple(0.0001),
        clip = Some(1d)
      )

      val (_, net, learningCurve, _, _) = IOLoops
        .epochs(
          model = model,
          optimizerFactory = optimizer,
          trainBatchesOverEpoch = trainEpochs,
          validationBatchesOverEpoch = None,
          epochs = 1
        )
        .unsafeRunSync()

      val textVariable = Text
        .sequencePrediction(
          List("time machine").map(t =>
            Text.charsToIntegers(t, vocab).map(_.toLong)
          ),
          device,
          net.module.lift,
          lookAhead
        )
      val text = Text.convertIntegersToText(textVariable, rvocab)

      assert(text == Vector(" the the t"))

      val textVariables2 = Text
        .sequencePredictionBeam(
          List("time machine")
            .map(t => Text.charsToIntegers(t, vocab).map(_.toLong))
            .head,
          device,
          net.module.lift,
          lookAhead,
          0,
          1
        )

      val text2 = textVariables2.map(v =>
        (
          Text.convertIntegersToText(v._1, rvocab).mkString,
          v._2
        )
      )

      assert(
        text2.map(_._1) == List("e theeeeeee", "ed and thee", "ed and and ")
      )

      assert(learningCurve.last._2 < 8d)
      ()
    }
  }

}
