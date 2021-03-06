package lamp.nn

import org.saddle._
import org.scalatest.funsuite.AnyFunSuite
import lamp.autograd._

import lamp.util.NDArray
import lamp.Scope
import lamp.STen

class AttentionSuite extends AnyFunSuite {

  test("sequence mask") {
    Scope.root { implicit scope =>
      val nd3x2L = NDArray(Array(0L, 1L, 0L, 1L, 0L, 0L), List(3, 2))
      val nd2x3 = NDArray(vec.ones(6).toArray, List(2, 3))
      val tokens = NDArray.tensorFromLongNDArray(nd3x2L, false)
      val maskable = NDArray.tensorFromNDArray(nd2x3, false)
      val masked = Attention.sequenceMask(
        tokens = const(STen.owned(tokens)),
        maskable = const(STen.owned(maskable)),
        maskedToken = 1L,
        fill = -1d
      )
      val maskedND = NDArray.tensorToNDArray(masked.value.value)
      val expected = NDArray(
        Array(1d, 1d, 1d, -1d, -1d, 1d),
        List(2, 3)
      )
      assert(maskedND.toVec == expected.toVec)
      ()
    }
  }
  test("dot product attention") {
    Scope.root { implicit scope =>
      val nd2x3L = NDArray.tensorFromLongNDArray(
        NDArray(Array(0L, 1L, 0L, 1L, 0L, 0L), List(2, 3))
      )

      /** batch1: 1 0
        * batch2: 0 1
        * batch3: 1 0
        */
      val nd3x2query = NDArray.tensorFromNDArray(
        NDArray(Array(1d, 0d, 0d, 1d, 1d, 0d), List(3, 2))
      )

      /** time1:
        *   batch1: 1 0
        *   batch2: 1 0
        *   batch3: 1 0
        * time2:
        *   batch1: 0 1
        *   batch2: 0 1
        *   batch3: 0 1
        */
      val nd2x3x2kv =
        NDArray.tensorFromNDArray(
          NDArray(
            Array(1d, 0d, 1d, 0d, 1d, 0d, 0d, 1d, 0d, 1d, 0d, 1d),
            List(2, 3, 2)
          )
        )

      val result = NDArray.tensorToNDArray(
        Attention
          .dotProductAttention(
            query = const(STen.owned(nd3x2query)),
            keyvalue = const(STen.owned(nd2x3x2kv)),
            tokens = const(STen.owned(nd2x3L)),
            padToken = 1L
          )
          .value
          .value
      )
      val expected = NDArray(
        Array(1d, 0d, 0d, 1d, 0.6697615493266569, 0.3302384506733431),
        List(3, 2)
      )

      assert(result.toVec == expected.toVec)
      ()
    }
  }

}
