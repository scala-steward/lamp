package lamp.example.lm

import lamp._
import lamp.data._
import java.io.File

import lamp.example.lm.Model
object Inference {
  def inference(config: CliConfig, codec: Codec)(scope: Scope) =
    Scope.bracket(scope) { implicit scope =>
      
      val device =
        if (config.gpus.nonEmpty) CudaDevice(config.gpus.head) else CPU

      val model = Model.allocateModel(device).module

      val checkpointedState = config.checkpointSave
        .map { state =>
          StateIO
            .readFromFile(new File(state), device)
            .asInstanceOf[SimpleLoopState]
        }
        .getOrElse(throw new RuntimeException("Can't load"))

      model.load(checkpointedState.model)

      val modelAsEval = model.languageModel.asEval

      val rawPrefix = config.extend.get.getBytes("US-ASCII")

      val encodedPrefix = codec.encode(rawPrefix)

      lamp.data.languagemodel
        .autoregressiveInference(
          modelAsEval,
          modelBlockSize = Model.contextLength,
          prefix = encodedPrefix,
          length = config.extendLength,
          temperature = config.samplingTemperature
        )(scope)
        .map { inferred =>
          val decoded = codec.decode(inferred)

          scribe.info(s"Extended:\n${new String(decoded)}\n")

        }
    }
}
