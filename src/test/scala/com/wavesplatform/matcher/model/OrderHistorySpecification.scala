package com.wavesplatform.matcher.model

import com.google.common.base.Charsets
import com.wavesplatform.WithDB
import com.wavesplatform.matcher.MatcherTestData
import com.wavesplatform.matcher.api.DBUtils
import com.wavesplatform.matcher.model.Events.{OrderAdded, OrderCanceled, OrderExecuted}
import com.wavesplatform.state.ByteStr
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import scorex.account.{Address, PrivateKeyAccount}
import scorex.transaction.AssetId
import scorex.transaction.assets.exchange.{AssetPair, Order}

import scala.collection.mutable

class OrderHistorySpecification
    extends PropSpec
    with WithDB
    with PropertyChecks
    with Matchers
    with MatcherTestData
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  private def mkAssetId(prefix: String) = {
    val prefixBytes = prefix.getBytes(Charsets.UTF_8)
    Some(ByteStr((prefixBytes ++ Array.fill[Byte](32 - prefixBytes.length)(0.toByte)).take(32)))
  }

  val pair = AssetPair(mkAssetId("WCT"), mkAssetId("BTC"))
  var oh   = new OrderHistory(db, matcherSettings)

  override def beforeEach(): Unit = {
    super.beforeEach()
    oh = new OrderHistory(db, matcherSettings)
  }

  private def activeOrderIds(address: Address, assetIds: Set[Option[AssetId]]): Seq[ByteStr] =
    DBUtils.ordersByAddress(db, address, assetIds, activeOnly = true, matcherSettings.maxOrdersPerRequest).map(_._1.id())

  private def allOrderIds(address: Address, assetIds: Set[Option[AssetId]]): Seq[ByteStr] =
    DBUtils.ordersByAddress(db, address, assetIds, activeOnly = false, matcherSettings.maxOrdersPerRequest).map(_._1.id())

  property("New buy order added") {
    val ord = buy(pair, 0.0007, 10000)

    val lo = LimitOrder(ord)
    oh.orderAccepted(OrderAdded(lo))

    val info = oh.orderInfo(ord.id())
    withClue("info") {
      info.status shouldBe LimitOrder.Accepted
      info shouldBe OrderInfo(ord.amount, 0, canceled = false, Some(lo.minAmountOfAmountAsset), ord.matcherFee, Some(0L))
    }

    withClue("reserved assets") {
      oh.openVolume(ord.senderPublicKey, pair.amountAsset) shouldBe 0L
      oh.openVolume(ord.senderPublicKey, pair.priceAsset) shouldBe 7L
      oh.openVolume(ord.senderPublicKey, None) shouldBe ord.matcherFee
    }

    activeOrderIds(ord.senderPublicKey, Set(pair.priceAsset)) shouldBe Seq(ord.id())
  }

  property("New sell order added") {
    val ord = sell(pair, 0.0007, 10000)

    val lo = LimitOrder(ord)
    oh.orderAccepted(OrderAdded(LimitOrder(ord)))

    val info = oh.orderInfo(ord.id())
    withClue("info") {
      info.status shouldBe LimitOrder.Accepted
      info shouldBe OrderInfo(ord.amount, 0, canceled = false, Some(lo.minAmountOfAmountAsset), ord.matcherFee, Some(0L))
    }

    withClue("reserved assets") {
      oh.openVolume(ord.senderPublicKey, pair.amountAsset) shouldBe 10000L
      oh.openVolume(ord.senderPublicKey, pair.priceAsset) shouldBe 0L
      oh.openVolume(ord.senderPublicKey, None) shouldBe ord.matcherFee
    }

    activeOrderIds(ord.senderPublicKey, Set(pair.amountAsset)) shouldBe Seq(ord.id())
  }

  property("New buy WAVES order added") {
    val pair = AssetPair(None, mkAssetId("BTC"))
    val ord  = buy(pair, 0.008, 1000, matcherFee = Some(3000))
    val lo   = LimitOrder(ord)

    oh.orderAccepted(OrderAdded(lo))

    val info = oh.orderInfo(ord.id())
    withClue("info") {
      info.status shouldBe LimitOrder.Accepted
      info shouldBe OrderInfo(ord.amount, 0, canceled = false, Some(lo.minAmountOfAmountAsset), ord.matcherFee, Some(0L))
    }

    withClue("reserved assets considering amount of received WAVES") {
      oh.openVolume(ord.senderPublicKey, pair.amountAsset) shouldBe 2000L
      oh.openVolume(ord.senderPublicKey, pair.priceAsset) shouldBe 8L
    }

    activeOrderIds(ord.senderPublicKey, Set(pair.priceAsset)) shouldBe Seq(ord.id())
  }

  property("New sell WAVES order added") {
    val pair = AssetPair(None, mkAssetId("BTC"))
    val ord  = sell(pair, 0.0008, 10000)
    val lo   = LimitOrder(ord)

    oh.orderAccepted(OrderAdded(lo))
    oh.orderInfo(ord.id()).status shouldBe LimitOrder.Accepted
    oh.openVolume(ord.senderPublicKey, pair.amountAsset) shouldBe 10000L + ord.matcherFee
    oh.openVolume(ord.senderPublicKey, pair.priceAsset) shouldBe 0L

    activeOrderIds(ord.senderPublicKey, Set(pair.amountAsset)) shouldBe Seq(ord.id())
  }

  property("Should not reserve fee, if seller receives more WAVES than total fee in sell order") {
    val pair = AssetPair(mkAssetId("BTC"), None)
    val ord  = sell(pair, 0.01, 100000, matcherFee = Some(1000L))

    oh.orderAccepted(OrderAdded(LimitOrder(ord)))

    val oi = oh.orderInfo(ord.id())
    oi.status shouldBe LimitOrder.Accepted

    oh.openVolume(ord.senderPublicKey, pair.priceAsset) shouldBe 0L
  }

  property("Should not reserve fee, if buyer receives more WAVES than total fee in buy order") {
    val pair = AssetPair(None, mkAssetId("BTC"))
    val ord  = buy(pair, 0.0007, 100000, matcherFee = Some(1000L))

    oh.orderAccepted(OrderAdded(LimitOrder(ord)))

    val oi = oh.orderInfo(ord.id())
    oi.status shouldBe LimitOrder.Accepted

    oh.openVolume(ord.senderPublicKey, pair.amountAsset) shouldBe 0L
  }

  property("Two sell orders added") {
    val pk   = PrivateKeyAccount("private".getBytes("utf-8"))
    val pair = AssetPair(None, mkAssetId("BTC"))
    val ord1 = sell(pair, 0.0005, 10000, Some(pk), matcherFee = Some(30000L))
    val ord2 = sell(pair, 0.0008, 16000, Some(pk), matcherFee = Some(30000L))

    oh.orderAccepted(OrderAdded(LimitOrder(ord1)))
    oh.orderAccepted(OrderAdded(LimitOrder(ord2)))

    withClue("all orders accepted") {
      oh.orderInfo(ord1.id()).status shouldBe LimitOrder.Accepted
      oh.orderInfo(ord2.id()).status shouldBe LimitOrder.Accepted
    }

    withClue("correction was used to reserve assets") {
      oh.openVolume(ord1.senderPublicKey, pair.amountAsset) shouldBe ord1.amount + ord1.matcherFee + ord2.amount + ord2.matcherFee
      oh.openVolume(ord1.senderPublicKey, pair.priceAsset) shouldBe 0L
    }

    activeOrderIds(ord1.senderPublicKey, Set(pair.priceAsset, pair.amountAsset)).toSet shouldBe Set(ord1.id(), ord2.id())
  }

  property("allowed add, then cancel, then add the same order") {
    val pair = AssetPair(mkAssetId("Alice"), None)

    val counter   = sell(pair, 200000000L, 100, matcherFee = Some(300000))
    val submitted = buy(pair, 200000000L, 130, matcherFee = Some(300000))

    oh.orderAccepted(OrderAdded(LimitOrder(counter)))
    oh.orderCanceled(OrderCanceled(LimitOrder(counter), unmatchable = false))
    oh.orderAccepted(OrderAdded(LimitOrder(counter)))

    val exec = OrderExecuted(LimitOrder(submitted), LimitOrder(counter))
    oh.orderExecuted(exec)

    oh.orderInfo(submitted.id()).status shouldBe LimitOrder.PartiallyFilled(100)
  }

  property("Buy WAVES order filled exactly") {
    val pair      = AssetPair(None, mkAssetId("BTC"))
    val counter   = buy(pair, 0.0008, 100000, matcherFee = Some(2000L))
    val submitted = sell(pair, 0.0007, 100000, matcherFee = Some(1000L))

    oh.orderAccepted(OrderAdded(LimitOrder(counter)))

    val exec = OrderExecuted(LimitOrder(submitted), LimitOrder(counter))
    oh.orderExecuted(exec)

    withClue("executed exactly") {
      exec.executedAmount shouldBe counter.amount
      oh.orderInfo(counter.id()).status shouldBe LimitOrder.Filled(exec.executedAmount)
      oh.orderInfo(submitted.id()).status shouldBe LimitOrder.Filled(exec.executedAmount)
    }

    withClue(s"has no reserved assets, counter.senderPublicKey: ${counter.senderPublicKey}, counter.order.id=${counter.id()}") {
      oh.openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0L
      oh.openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0L
      activeOrderIds(counter.senderPublicKey, Set(None)) shouldBe empty
    }

    withClue(s"has no reserved assets, submitted.senderPublicKey: ${submitted.senderPublicKey}, submitted.order.id=${submitted.id()}") {
      oh.openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0L
      oh.openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0L
      activeOrderIds(submitted.senderPublicKey, Set(None)) shouldBe empty
    }
  }

  property("Buy WAVES order filled with remainder") {
    val pair      = AssetPair(None, mkAssetId("BTC"))
    val counter   = sell(pair, 0.00000238, 840340L, matcherFee = Some(300000L))
    val submitted = buy(pair, 0.00000238, 425532L, matcherFee = Some(300000L))

    oh.orderAccepted(OrderAdded(LimitOrder(counter)))
    val counterOrderInfo1 = oh.orderInfo(counter.id())
    withClue(s"account checks, counter.senderPublicKey: ${counter.senderPublicKey}, counter.order.id=${counter.id()}") {
//      (counterOrderInfo1.remainingSpend + counterOrderInfo1.remainingFee) shouldBe counter.amount + counter.matcherFee
//      oh.openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe counterOrderInfo1.remainingSpend + counterOrderInfo1.remainingFee // todo
      oh.openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0L
      activeOrderIds(counter.senderPublicKey, Set(None)) shouldBe Seq(counter.id())
    }

    val exec = OrderExecuted(LimitOrder(submitted), LimitOrder(counter))
    exec.executedAmount shouldBe 420169L

    oh.orderExecuted(exec)
    val counterOrderInfo2 = oh.orderInfo(counter.id())
    //val counterOrderInfoDiff = OrderHistory.orderInfoDiffExecuted(counter, counterOrderInfo1, counterOrderInfo2)
    println(s"""|
          |counterOrderInfo1:
          |$counterOrderInfo1
          |remaining: ${counterOrderInfo1.remaining}
          |remainingFee: ${counterOrderInfo1.remainingFee}
          |
          |counterOrderInfo2:
          |$counterOrderInfo2
          |remaining: ${counterOrderInfo2.remaining}
          |remainingFee: ${counterOrderInfo2.remainingFee}
          |
          |orderInfoDiff:
          |calculated remaining diff: ${counterOrderInfo2.remaining - counterOrderInfo1.remaining}
          |calculated remainingFee diff: ${counterOrderInfo2.remainingFee - counterOrderInfo1.remainingFee}
          |calculated remaining total: ${counterOrderInfo2.remaining - counterOrderInfo1.remaining + counterOrderInfo2.remainingFee - counterOrderInfo1.remainingFee}
          |
          |""".stripMargin)

    val counterOrderInfo = oh.orderInfo(counter.id())
    withClue(s"counter: ${submitted.id()}") {
      counterOrderInfo.filled shouldBe exec.executedAmount

      exec.counterRemainingAmount shouldBe 420171L
      exec.counterRemainingAmount shouldBe counter.amount - exec.executedAmount
      exec.counterRemainingAmount shouldBe counterOrderInfo.remaining

      exec.counterRemainingFee shouldBe 150001L
      exec.counterRemainingFee shouldBe counterOrderInfo.remainingFee

      counterOrderInfo.status shouldBe LimitOrder.PartiallyFilled(exec.executedAmount)
    }

    val submittedOrderInfo = oh.orderInfo(submitted.id())
    withClue(s"submitted: ${counter.id()}") {
      exec.submittedRemainingAmount shouldBe 5363L
      exec.submittedRemainingAmount shouldBe submitted.amount - exec.executedAmount
      exec.submittedRemainingAmount shouldBe submittedOrderInfo.remaining

      exec.submittedRemainingFee shouldBe 3781L
      exec.submittedRemainingFee shouldBe submittedOrderInfo.remainingFee

      submittedOrderInfo.status shouldBe LimitOrder.Filled(exec.executedAmount)
    }

    // see OrderBookActor.handleMatchEvent
    oh.orderAccepted(OrderAdded(exec.submittedRemaining))

    withClue(s"account checks, counter.senderPublicKey: ${counter.senderPublicKey}, counter.order.id=${counter.id()}") {
      val remainingSpend = counter.amount - counterOrderInfo.totalSpend(LimitOrder(counter))
      oh.openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe remainingSpend + counterOrderInfo.remainingFee
      oh.openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0L
      activeOrderIds(counter.senderPublicKey, Set(None)) shouldBe Seq(counter.id())
    }

    withClue(s"account checks, submitted.senderPublicKey: ${submitted.senderPublicKey}, submitted.order.id=${submitted.id()}") {
      oh.openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe 0L
      oh.openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe 0L
      activeOrderIds(submitted.senderPublicKey, Set(None)) shouldBe empty
    }
  }

  property("Sell WAVES order - filled, buy order - partial") {
    val pair      = AssetPair(None, mkAssetId("BTC"))
    val counter   = sell(pair, 0.0008, 100000000, matcherFee = Some(2000L))
    val submitted = buy(pair, 0.00085, 120000000, matcherFee = Some(1000L))

    println("\norderAccepted start\n")
    // check info
    oh.orderAccepted(OrderAdded(LimitOrder(counter)))
    val exec = OrderExecuted(LimitOrder(submitted), LimitOrder(counter))
    oh.orderExecuted(exec)
    oh.orderAccepted(OrderAdded(exec.submittedRemaining))

    val counterOrderInfo = oh.orderInfo(counter.id())
    withClue(s"counter: ${counter.id()}") {
      exec.counterRemainingAmount shouldBe 0L
      exec.counterRemainingAmount shouldBe counterOrderInfo.remaining

      exec.counterRemainingFee shouldBe 0L
      exec.counterRemainingFee shouldBe counterOrderInfo.remainingFee

      counterOrderInfo.status shouldBe LimitOrder.Filled(100000000)
    }

    val submittedOrderInfo = oh.orderInfo(submitted.id())
    withClue(s"submitted: ${submitted.id()}") {
      exec.submittedRemainingAmount shouldBe 20000000L
      exec.submittedRemainingAmount shouldBe submittedOrderInfo.remaining

      exec.submittedRemainingFee shouldBe 167L
      exec.submittedRemainingFee shouldBe submittedOrderInfo.remainingFee

      submittedOrderInfo.status shouldBe LimitOrder.PartiallyFilled(100000000)
    }

    withClue(s"account checks, submitted.senderPublicKey: ${submitted.senderPublicKey}, submitted.order.id=${submitted.id()}") {
      oh.openVolume(submitted.senderPublicKey, pair.amountAsset) shouldBe
        math.max(0L,
                 OrderInfo.safeSum(LimitOrder.getPartialFee(submitted.matcherFee, submitted.amount, submitted.amount - counter.amount), -20000000L))
      oh.openVolume(submitted.senderPublicKey, pair.priceAsset) shouldBe (BigDecimal(0.00085) * 20000000L).toLong // <--
      activeOrderIds(submitted.senderPublicKey, Set(pair.priceAsset)) shouldBe Seq(submitted.id())
    }
  }

  property("Buy WAVES order - filled with 2 steps, sell order - partial") {
    val pair       = AssetPair(None, mkAssetId("BTC"))
    val counter    = buy(pair, 0.0008, 100000000, matcherFee = Some(300001L))
    val submitted1 = sell(pair, 0.00075, 50000000, matcherFee = Some(300001L))
    val submitted2 = sell(pair, 0.0008, 80000000, matcherFee = Some(300001L)) // works with 300000L

    println(s"==== before added")
    oh.orderAccepted(OrderAdded(LimitOrder(counter)))
    println(s"== order added counter: ${counter.id()}")
    val exec1 = OrderExecuted(LimitOrder(submitted1), LimitOrder(counter)) //LimitOrder.limitOrder(ord1.price, ord1.amount - ord2.amount, 150001L, ord1))
    println(s"== order executed: submitted1(${submitted1.id()}), counter (${counter.id()}")

    println(s"==== before executed 1")
    oh.orderExecuted(exec1)

    println(s"""
         |orderInfo:
         |counter (id=${counter.id()}, status=${oh.orderInfo(counter.id()).status}): ${oh.orderInfo(counter.id())}
         |submitted1 (id=${submitted1.id()}, status=${oh.orderInfo(submitted1.id()).status}): ${oh.orderInfo(submitted1.id())}
         |submitted2 (id=${submitted2.id()}, status=${oh.orderInfo(submitted2.id()).status}): ${oh.orderInfo(submitted2.id())}
         |""".stripMargin)

    val counterInfo1    = oh.orderInfo(counter.id())
    val submitted1Info1 = oh.orderInfo(submitted1.id())

    // ord3 & ord1
    exec1.counterRemainingAmount shouldBe counterInfo1.remaining
    exec1.counterRemainingFee shouldBe counterInfo1.remainingFee
    counterInfo1.status shouldBe LimitOrder.PartiallyFilled(50000000)

    exec1.submittedRemainingAmount shouldBe submitted1Info1.remaining
    exec1.submittedRemainingFee shouldBe submitted1Info1.remainingFee
    submitted1Info1.status shouldBe LimitOrder.Filled(50000000)

    oh.orderInfo(submitted2.id()).status shouldBe LimitOrder.NotFound

    println(s"==== before executed 2")
    val exec2 = OrderExecuted(LimitOrder(submitted2), exec1.counterRemaining) // ord1
    oh.orderExecuted(exec2)
    oh.orderAccepted(OrderAdded(exec2.submittedRemaining))
    println(s"==== after executed 2")

    println(s"""== order executed: ${submitted2.id()}, ${exec1.counter.order.id()}
               |exec2.counterRemaining (counter: ${counter.id()}): ${exec2.counterRemaining}
               |exec2.submittedRemaining (submitted2: ${submitted2.id()}): ${exec2.submittedRemaining}
               |""".stripMargin)

    println(s"""
         |orderInfo:
         |counter (id=${counter.id()}, status=${oh.orderInfo(counter.id()).status}): ${oh.orderInfo(counter.id())}
         |submitted1 (id=${submitted1.id()}, status=${oh.orderInfo(submitted1.id()).status}): ${oh.orderInfo(submitted1.id())}
         |submitted2 (id=${submitted2.id()}, status=${oh.orderInfo(submitted2.id()).status}): ${oh.orderInfo(submitted2.id())}
         |""".stripMargin)

    val counterInfo2 = oh.orderInfo(counter.id())
    withClue(s"counter: ${counter.id()}") {
      println(counterInfo2)
      exec2.counterRemainingAmount shouldBe counterInfo2.remaining
      exec2.counterRemainingFee shouldBe counterInfo2.remainingFee // 1 vs 0
      oh.orderInfo(counter.id()).status shouldBe LimitOrder.Filled(100000000) // fails
    }

    oh.orderInfo(submitted1.id()).status shouldBe LimitOrder.Filled(50000000)

    val submitted2Info1 = oh.orderInfo(submitted2.id())
    exec2.submittedRemainingAmount shouldBe submitted2Info1.remaining
    exec2.submittedRemainingFee shouldBe submitted2Info1.remainingFee
    oh.orderInfo(submitted2.id()).status shouldBe LimitOrder.PartiallyFilled(50000000)

    oh.orderAccepted(OrderAdded(exec2.submittedRemaining))

    oh.openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0L
    oh.openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0L
    activeOrderIds(counter.senderPublicKey, Set(None)) shouldBe empty

    oh.openVolume(submitted1.senderPublicKey, pair.priceAsset) shouldBe 0L
    oh.openVolume(submitted1.senderPublicKey, pair.amountAsset) shouldBe 0L
    activeOrderIds(submitted1.senderPublicKey, Set(None)) shouldBe empty

    println(s"""ord3:
         |amount volume: ${submitted2Info1.amount - submitted2Info1.filled + submitted2Info1.remainingFee}
         |o3Info2.amount: ${submitted2Info1.amount}
         |o3Info2.filled: ${submitted2Info1.filled}
         |o3Info2.remainingFee: ${submitted2Info1.remainingFee}
         |o3Info2.remaining: ${submitted2Info1.remaining}
         |""".stripMargin)
    withClue(s"account checks, ord3.senderPublicKey: ${submitted2.senderPublicKey}, ord3.order.id=${submitted2.id()}") {
      val lo             = LimitOrder(submitted2)
      val remainingSpend = lo.getSpendAmount - submitted2Info1.totalSpend(lo)
      oh.openVolume(submitted2.senderPublicKey, pair.amountAsset) shouldBe (remainingSpend + submitted2Info1.remainingFee) // ord3.matcherFee * 3 / 8 + 30000000L // or it's ok?
      oh.openVolume(submitted2.senderPublicKey, pair.priceAsset) shouldBe 0L
      activeOrderIds(submitted2.senderPublicKey, Set(pair.amountAsset)) shouldBe Seq(submitted2.id())
    }
  }

  property("Total execution of two counter orders and the one submitted") {
    val pair = AssetPair(mkAssetId("Alice"), None)

    val counter1  = buy(pair, 190000000L, 150, matcherFee = Some(300000))
    val counter2  = buy(pair, 200000000L, 200, matcherFee = Some(300000))
    val submitted = sell(pair, 210000000L, 350, matcherFee = Some(300000))

    println(s"""|counter1: ${counter1.id()}
                |counter2: ${counter2.id()}
                |submitted: ${submitted.id()}""".stripMargin)

    oh.orderAccepted(OrderAdded(LimitOrder(counter1)))
    oh.orderAccepted(OrderAdded(LimitOrder(counter2)))

    val exec1 = OrderExecuted(LimitOrder(submitted), LimitOrder(counter1))
    oh.orderExecuted(exec1)
    oh.orderAccepted(OrderAdded(exec1.submittedRemaining))
    oh.orderExecuted(OrderExecuted(exec1.submittedRemaining, LimitOrder(counter2)))

    oh.orderInfo(submitted.id()).status shouldBe LimitOrder.Filled(350)
  }

  property("Partially with own order") {
    val pk        = PrivateKeyAccount("private".getBytes("utf-8"))
    val pair      = AssetPair(None, mkAssetId("BTC"))
    val counter   = buy(pair, 0.0008, 100000000, Some(pk), Some(300000L))
    val submitted = sell(pair, 0.00079, 210000000, Some(pk), Some(300000L))

    println(s"""|
          |before:
          |oh.openVolume(${counter.senderPublicKey}, ${pair.amountAsset}): ${oh.openVolume(counter.senderPublicKey, pair.amountAsset)}
          |""".stripMargin)
    oh.orderAccepted(OrderAdded(LimitOrder(counter)))
    println(s"""|
          |after accept:
          |oh.openVolume(${counter.senderPublicKey}, ${pair.amountAsset}): ${oh.openVolume(counter.senderPublicKey, pair.amountAsset)}
          |""".stripMargin)

    val exec = OrderExecuted(LimitOrder(submitted), LimitOrder(counter))
    println(s"""|
                |amount: ${exec.executedAmount}
                |
                |counter:
                |will receive: ${exec.counter.getReceiveAmount} ${exec.counter.rcvAsset}
                |will spend: ${exec.counter.getSpendAmount} ${exec.counter.spentAsset}
                |""".stripMargin)

    println(s"""|
                |submitted:
                |will receive: ${exec.submitted.getReceiveAmount} ${exec.submitted.rcvAsset}
                |will spend: ${exec.submitted.getSpendAmount} ${exec.submitted.spentAsset}
                |""".stripMargin)

    oh.orderExecuted(exec)

    println(s"""|
          |after execute:
          |oh.openVolume(${counter.senderPublicKey}, ${pair.amountAsset}): ${oh.openVolume(counter.senderPublicKey, pair.amountAsset)}
          |""".stripMargin)

    val counterOrderInfo = oh.orderInfo(counter.id())
    withClue(s"counter: ${counter.id()}") {
      exec.counterRemainingAmount shouldBe 0L
      exec.counterRemainingAmount shouldBe counterOrderInfo.remaining

      exec.counterRemainingFee shouldBe 0L
      exec.counterRemainingFee shouldBe counterOrderInfo.remainingFee

      counterOrderInfo.status shouldBe LimitOrder.Filled(100000000)
    }

    val submittedOrderInfo = oh.orderInfo(submitted.id())
//    withClue(s"submitted: ${submitted.id()}") {
//      exec.submittedRemainingAmount shouldBe 110000000L
//      exec.submittedRemainingAmount shouldBe submittedOrderInfo.remaining
//
//      exec.submittedRemainingFee shouldBe 157143L
//      exec.submittedRemainingFee shouldBe submittedOrderInfo.remainingFee

    /*
      withClue(s"account checks, counter.senderPublicKey: ${counter.senderPublicKey}, counter.order.id=${counter.id()}") {
      // Will receive 165900 < fee
      // 208!
      println(s"""|
            |exec.executedAmount: ${exec.executedAmount}
            |
            |exec.submittedRemainingAmount: ${exec.submittedRemainingAmount}
            |exec.submittedRemainingFee: ${exec.submittedRemainingFee}
            |
            |exec.counterRemainingAmount: ${exec.counterRemainingAmount}
            |exec.counterRemainingFee: ${exec.counterRemainingFee}
            |
            |exec.counter.amountOfAmountAsset: ${exec.counter.amountOfAmountAsset}
            |exec.counter.minAmountOfAmountAsset: ${exec.counter.minAmountOfAmountAsset}
            |exec.submitted.amountOfAmountAsset: ${exec.submitted.amountOfAmountAsset}
            |exec.submitted.minAmountOfAmountAsset: ${exec.submitted.minAmountOfAmountAsset}
            |""".stripMargin)
      oh.openVolume(pk, pair.amountAsset) shouldBe exec.submitted.amountOfAmountAsset - exec.executedAmount + LimitOrder
        .getPartialFee(
          submitted.matcherFee,
          submitted.amount,
          submitted.amount - exec.executedAmount
        ) + exec.counter.amountOfAmountAsset - exec.executedAmount + LimitOrder
        .getPartialFee(
          counter.matcherFee,
          counter.amount,
          counter.amount - exec.executedAmount
        )
      oh.openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0L
      activeOrderIds(counter.senderPublicKey, Set(pair.amountAsset)) shouldBe Seq(submitted.id())
    }
     */

    val expectedAmountReserved = counterOrderInfo.remainingFee + submittedOrderInfo.remaining + submittedOrderInfo.remainingFee
    expectedAmountReserved shouldBe 110157143L

    oh.orderAccepted(OrderAdded(exec.submittedRemaining))

    // check price
    oh.openVolume(pk, pair.amountAsset) shouldBe expectedAmountReserved
    oh.openVolume(pk, pair.priceAsset) shouldBe 0L
    activeOrderIds(pk, Set(pair.amountAsset)) shouldBe Seq(submitted.id())
  }

  property("Cancel buy order") {
    val ord1 = buy(pair, 0.0008, 100000000, matcherFee = Some(300000L))

    oh.orderAccepted(OrderAdded(LimitOrder(ord1)))
    oh.orderCanceled(OrderCanceled(LimitOrder(ord1), unmatchable = false))

    oh.orderInfo(ord1.id()).status shouldBe LimitOrder.Cancelled(0)

    oh.openVolume(ord1.senderPublicKey, pair.amountAsset) shouldBe 0L
    oh.openVolume(ord1.senderPublicKey, pair.priceAsset) shouldBe 0L
    activeOrderIds(ord1.senderPublicKey, Set(None)) shouldBe empty
  }

  property("Cancel sell order") {
    val ord1 = sell(pair, 0.0008, 100000000, matcherFee = Some(300000L))

    oh.orderAccepted(OrderAdded(LimitOrder(ord1)))
    oh.orderCanceled(OrderCanceled(LimitOrder(ord1), unmatchable = false))

    oh.orderInfo(ord1.id()).status shouldBe LimitOrder.Cancelled(0)

    oh.openVolume(ord1.senderPublicKey, pair.amountAsset) shouldBe 0L
    oh.openVolume(ord1.senderPublicKey, pair.priceAsset) shouldBe 0L
    activeOrderIds(ord1.senderPublicKey, Set(None)) shouldBe empty
  }

  property("Cancel partially executed order") {
    val pair      = AssetPair(None, mkAssetId("BTC"))
    val counter   = sell(pair, 0.0008, 2100000000, matcherFee = Some(300000L))
    val submitted = buy(pair, 0.00081, 1000000000, matcherFee = Some(300000L))

    oh.orderAccepted(OrderAdded(LimitOrder(counter)))
    val exec1 = OrderExecuted(LimitOrder(submitted), LimitOrder(counter))
    oh.orderExecuted(exec1)
    oh.orderCanceled(OrderCanceled(exec1.counter.partial(exec1.counterRemainingAmount, exec1.counterRemainingFee), unmatchable = false))

    oh.orderInfo(counter.id()).status shouldBe LimitOrder.Cancelled(1000000000)
    oh.orderInfo(submitted.id()).status shouldBe LimitOrder.Filled(1000000000)

    oh.openVolume(counter.senderPublicKey, pair.amountAsset) shouldBe 0L
    oh.openVolume(counter.senderPublicKey, pair.priceAsset) shouldBe 0L
    activeOrderIds(counter.senderPublicKey, Set(None)) shouldBe empty
    activeOrderIds(submitted.senderPublicKey, Set(None)) shouldBe empty
  }

  property("Delete order") {
    val pk        = PrivateKeyAccount("private".getBytes("utf-8"))
    val pair      = AssetPair(None, mkAssetId("BTC"))
    val counter   = buy(pair, 0.0008, 210000000, Some(pk), Some(300000L))
    val submitted = sell(pair, 0.00079, 100000000, Some(pk), Some(300000L))

    oh.orderAccepted(OrderAdded(LimitOrder(counter)))
    val exec1 = OrderExecuted(LimitOrder(submitted), LimitOrder(counter))
    oh.orderExecuted(exec1)

    val counterInfo = oh.orderInfo(counter.id())
    counterInfo.status shouldBe LimitOrder.PartiallyFilled(100000000)

    val submittedInfo = oh.orderInfo(submitted.id())
    submittedInfo.status shouldBe LimitOrder.Filled(100000000)

    val expectedAmountReserved = counterInfo.remainingFee
    println(s"expectedAmountReserved = $expectedAmountReserved")
    oh.openVolume(pk, pair.amountAsset) shouldBe 0 // We receive 210000000 >> 300000 WAVES

    val counterLo             = LimitOrder(counter)
    val expectedPriceReserved = counterLo.getSpendAmount - counterInfo.totalSpend(counterLo)
    println(s"expectedPriceReserved = $expectedPriceReserved")
    oh.openVolume(pk, pair.priceAsset) shouldBe expectedPriceReserved // was 0.0008 * 110000000L

    oh.deleteOrder(pk, counter.id()) shouldBe false
    oh.deleteOrder(pk, submitted.id()) shouldBe true

    activeOrderIds(pk, Set(pair.priceAsset)) shouldBe Seq(counter.id())
  }

  property("Sorting by status then timestamp") {
    val pk   = PrivateKeyAccount("private".getBytes("utf-8"))
    val pair = AssetPair(None, mkAssetId("BTC"))
    val ord1 = buy(pair, 0.0008, 110000000, Some(pk), Some(300000L), Some(1L)) // Filled
    val ord2 = buy(pair, 0.0006, 120000000, Some(pk), Some(300000L), Some(2L)) // Accepted
    val ord3 = buy(pair, 0.0005, 130000000, Some(pk), Some(300000L), Some(3L)) // Canceled
    val ord4 = sell(pair, 0.00079, 2100000000, Some(pk), Some(300000L), Some(4L)) // Partial
    val ord5 = buy(pair, 0.0004, 130000000, Some(pk), Some(300000L), Some(45)) // Accepted

    oh.orderAccepted(OrderAdded(LimitOrder(ord1)))
    oh.orderAccepted(OrderAdded(LimitOrder(ord2)))
    oh.orderAccepted(OrderAdded(LimitOrder(ord3)))
    val exec = OrderExecuted(LimitOrder(ord4), LimitOrder(ord1))
    oh.orderExecuted(exec)
    oh.orderAccepted(OrderAdded(exec.submittedRemaining))
    oh.orderCanceled(OrderCanceled(LimitOrder(ord3), unmatchable = false))
    oh.orderAccepted(OrderAdded(LimitOrder(ord5)))

    allOrderIds(ord1.senderPublicKey, Set.empty) shouldBe
      Seq(ord5.id(), ord4.id(), ord2.id(), ord3.id(), ord1.id())

    activeOrderIds(ord1.senderPublicKey, Set.empty) shouldBe
      Seq(ord5.id(), ord4.id(), ord2.id())
  }

  property("History with more than max limit") {
    val pk     = PrivateKeyAccount("private".getBytes("utf-8"))
    val pair   = AssetPair(None, mkAssetId("BTC"))
    val orders = mutable.Buffer.empty[Order]
    (0 until matcherSettings.maxOrdersPerRequest).foreach { i =>
      val o = buy(pair, 0.0008 + 0.00001 * i, 100000000, Some(pk), Some(300000L), Some(100L + i))
      orders += o
      oh.orderAccepted(OrderAdded(LimitOrder(o)))
    }

    oh.orderCanceled(OrderCanceled(LimitOrder(orders.last), unmatchable = false))

    val newOrder = buy(pair, 0.001, 100000000, Some(pk), Some(300000L), Some(1L))

    oh.orderAccepted(OrderAdded(LimitOrder(newOrder)))

    allOrderIds(pk, Set.empty) shouldBe orders.reverse.tail.map(_.id()) :+ newOrder.id()
  }

  property("History with more than max limit and canceled order") {
    val pk     = PrivateKeyAccount("private".getBytes("utf-8"))
    val pair   = AssetPair(None, mkAssetId("BTC"))
    val orders = mutable.Buffer.empty[Order]
    (0 until matcherSettings.maxOrdersPerRequest + 1).foreach { i =>
      val o = buy(pair, 0.0008 + 0.00001 * i, 100000000, Some(pk), Some(300000L), Some(100L + i))
      orders += o
      oh.orderAccepted(OrderAdded(LimitOrder(o)))
    }

    oh.orderCanceled(OrderCanceled(LimitOrder(orders.last), unmatchable = false))
    allOrderIds(pk, Set.empty) shouldBe orders.reverse.tail.map(_.id())
  }

  property("Open Portfolio for two assets") {
    val pk         = PrivateKeyAccount("private".getBytes("utf-8"))
    val ass1       = mkAssetId("ASS1")
    val ass2       = mkAssetId("ASS2")
    val pair1      = AssetPair(ass1, None)
    val pair2      = AssetPair(ass2, None)
    val matcherFee = 300000L
    val ord1       = sell(pair1, 0.0008, 10000, Some(pk), Some(matcherFee))
    val ord2       = sell(pair2, 0.0009, 10001, Some(pk), Some(matcherFee))

    oh.orderAccepted(OrderAdded(LimitOrder(ord1)))
    oh.orderAccepted(OrderAdded(LimitOrder(ord2)))

    DBUtils.reservedBalance(db, pk) shouldBe
      Map(
        None -> (2 * matcherFee - LimitOrder(ord1).getReceiveAmount - LimitOrder(ord2).getReceiveAmount),
        ass1 -> ord1.amount,
        ass2 -> ord2.amount
      )
  }
}
