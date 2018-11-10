package com.evolutiongaming.concurrent.async

import org.scalatest.{FunSuite, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}

class AsyncSpec extends FunSuite with Matchers {

  private val timeout = 3.seconds

  test("map") {
    Async(1).map(_ + 1) shouldEqual Async(2)
    Async(1).map(_ => throw Error) shouldEqual Async.failed(Error)
    Async.failed[Int](Error).map(_ + 1) shouldEqual Async.failed(Error)
    Async.async(1).map(_ + 1).await(timeout) shouldEqual Async(2)
    Async.async(1).await(timeout).map(_ + 1) shouldEqual Async(2)
  }

  test("flatMap") {
    Async(1).flatMap(_ => Async(2)) shouldEqual Async(2)
    Async(1).flatMap(_ => Async.failed(Error)) shouldEqual Async.failed(Error)
    Async(1).flatMap(_ => throw Error) shouldEqual Async.failed(Error)
    Async(1).flatMap(_ => Async.async(2)).await(timeout) shouldEqual Async(2)


    Async.failed(Error).flatMap(_ => Async(2)) shouldEqual Async.failed(Error)
    Async.failed(Error).flatMap(_ => Async.failed(Error)) shouldEqual Async.failed(Error)
    Async.failed(Error).flatMap(_ => throw Error) shouldEqual Async.failed(Error)
    Async.failed(Error).flatMap(_ => Async.async(2)) shouldEqual Async.failed(Error)


    Async.async(1).flatMap(_ => Async(2)).await(timeout) shouldEqual Async(2)
    Async.async(1).flatMap(_ => Async.failed(Error)).await(timeout) shouldEqual Async.failed(Error)
    Async.async(1).flatMap(_ => throw Error).await(timeout) shouldEqual Async.failed(Error)
    Async.async(1).flatMap(_ => Async.async(2)).await(timeout) shouldEqual Async(2)
  }

  test("value") {
    Async(1).value shouldEqual Some(Success(1))
    Async.failed(Error).value shouldEqual Some(Failure(Error))
    //    Async.never[Int].value shouldEqual None
  }

  test("get") {
    Async(1).get(timeout) shouldEqual 1
    the[Error.type] thrownBy Async.failed(Error).get(timeout)
    Async.async(1).get(timeout) shouldEqual 1
  }

  test("fold") {
    val list = (1 to 100).toList.map(_.toString)
    val fold = (s: String, e: String) => s + e
    val foldAsync = (s: String, e: Async[String]) => e.map(e => fold(s, e))
    val expected = list.foldLeft("")(fold)
    val completed = list.map(x => Async(x))
    Async.fold(completed, "")(foldAsync) shouldEqual Async(expected)

    val mixed = list.map(x => if (x.startsWith("2") || x.startsWith("4")) Async.async(x) else Async(x))
    Async.fold(mixed, "")(foldAsync).await() shouldEqual Async(expected)

    val inCompleted = list.map(x => Async.async(x))
    Async.fold(inCompleted, "")(foldAsync).await() shouldEqual Async(expected)
  }

  test("mapTry") {
    Async(1).mapTry(_.orElse(Success(2))).get(timeout) shouldEqual 1
    Async.failed(Error).mapTry(_.orElse(Success(2))).get(timeout) shouldEqual 2
    Async.async(1).mapTry(_.orElse(Success(2))).get(timeout) shouldEqual 1
  }

  test("redeem") {
    Async(1).redeem(_ => Async(2), _ => Async(3)).get(timeout) shouldEqual 3
    Async.failed(Error).redeem(_ => Async(2), _ => Async(3)).get(timeout) shouldEqual 2
    Async.async(1).redeem(_ => Async(2), _ => Async(3)).get(timeout) shouldEqual 3
  }

  test("redeemPure") {
    Async(1).redeemPure(_ => 2, _ => 3).get(timeout) shouldEqual 3
    Async.failed(Error).redeemPure(_ => 2, _ => 3).get(timeout) shouldEqual 2
    Async.async(1).redeemPure(_ => 2, _ => 3).get(timeout) shouldEqual 3
  }

  test("unit") {
    Async.unit shouldEqual Async(())
  }

  test("none") {
    Async.none[Int] shouldEqual Async(None)
  }

  test("nil") {
    Async.nil[Int] shouldEqual Async(Nil)
  }

  test("seq") {
    Async.seq[Int] shouldEqual Async(Seq.empty)
  }

  test("onComplete") {
    var n = 0
    Async(1).onComplete { x => n = x.getOrElse(2) }
    n shouldEqual 1

    Async.failed(Error).onComplete { x => n = x.getOrElse(2) }
    n shouldEqual 2
  }

  test("onFailure") {
    var n = 0
    Async(1).onFailure { _ => n = 1 }
    n shouldEqual 0

    Async.failed(Error).onFailure { _ => n = 2 }
    n shouldEqual 2
  }

  test("onSuccess") {
    var n = 0
    Async(1).onSuccess { _ => n = 1 }
    n shouldEqual 1

    Async.failed(Error).onSuccess { _ => n = 2 }
    n shouldEqual 1
  }

  test("recover") {
    Async(1).recover { case _ => 2 }.get(timeout) shouldEqual 1
    Async.failed(Error).recover { case _ => 2 }.get(timeout) shouldEqual 2
    Async.async(1).recover { case _ => 2 }.get(timeout) shouldEqual 1
  }

  test("recoverWith") {
    Async(1).recoverWith { case _ => Async(2) }.get(timeout) shouldEqual 1
    Async.failed(Error).recoverWith { case _ => Async(2) }.get(timeout) shouldEqual 2
    Async.async(1).recoverWith { case _ => Async(2) }.get(timeout) shouldEqual 1
  }

  test("toString") {
    Async(1).toString shouldEqual "Async(1)"
    Async.failed(Error).toString shouldEqual "Async(Error)"
    val promise = Promise[Int]
    Async(promise.future).toString shouldEqual "Async(<not completed>)"
  }

  test("flatten") {
    Async(Async(1)).flatten.get(timeout) shouldEqual 1
  }

  test("mapFailure") {
    Async(1).mapFailure(_ => Error) shouldEqual Async(1)
    Async.failed(Error).mapFailure(_ => Error1) shouldEqual Async.failed(Error1)
    the[Error1.type] thrownBy Async.async(throw Error).mapFailure { _ => Error1 }.get(timeout)
  }

  test("flatMapFailure") {
    Async(1).flatMapFailure(_ => Async(2)) shouldEqual Async(1)
    Async.failed(Error).flatMapFailure(_ => Async.failed(Error1)) shouldEqual Async.failed(Error1)
    the[Error1.type] thrownBy Async.async(throw Error).mapFailure { _ => Error1 }.get(timeout)
  }

  private case object Error extends RuntimeException with NoStackTrace {
    override def toString = "Error"
  }

  private case object Error1 extends RuntimeException with NoStackTrace {
    override def toString = "Error1"
  }
}
