package com.evolutiongaming.concurrent.async

import com.evolutiongaming.concurrent.FutureHelper._

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

sealed trait Async[+A] {

  def foreach[B](f: A => B): Unit

  def map[B](f: A => B): Async[B]

  def flatMap[B](f: A => Async[B]): Async[B]

  def mapTry[B](f: Try[A] => Try[B]): Async[B]

  def mapFailure(f: Throwable => Throwable): Async[A]

  def flatMapFailure[B >: A](f: Throwable => Async[B]): Async[B]

  def value(): Option[Try[A]]

  def future: Future[A]

  def get(timeout: Duration = Duration.Inf): A

  def await(timeout: Duration = Duration.Inf): Async[A]

  def onComplete[B](f: Try[A] => B): Unit

  def onSuccess[B](f: A => B): Unit

  def onFailure[B](f: Throwable => B): Unit

  def recover[B >: A](pf: PartialFunction[Throwable, B]): Async[B]

  def recoverWith[B >: A](pf: PartialFunction[Throwable, Async[B]]): Async[B]

  def redeem[B](err: Throwable => Async[B], succ: A => Async[B]): Async[B]

  def redeemPure[B](err: Throwable => B, succ: A => B): Async[B]

  final def flatten[B](implicit ev: A <:< Async[B]): Async[B] = flatMap(ev)

  final def unit: Async[Unit] = flatMap(_ => Async.unit)

  final def withFilter(p: A => Boolean): Async[A] = {
    map { v =>
      if (p(v)) v
      else throw new NoSuchElementException("Async.filter predicate is not satisfied")
    }
  }
}

object Async {
  import AsyncConverters._

  private val futureNone = Option.empty.async
  private val futureSeq = Seq.empty.async
  private val futureNil = Nil.async


  def apply[A](value: A): Async[A] = Succeed(value)

  def apply[A](future: Future[A])(implicit ec: ExecutionContext): Async[A] = {
    future.value match {
      case Some(value) => completed(value)
      case None        => InCompleted(future)
    }
  }

  def succeed[A](value: A): Async[A] = Succeed(value)

  def failed[A](value: Throwable): Async[A] = Failed(value)

  def completed[A](value: Try[A]): Async[A] = Completed(value)

  def async[A](f: => A)(implicit ec: ExecutionContext): Async[A] = InCompleted(Future(f)(ec))

  //  def never[A]: Async[A] = Async(Future.never)(CurrentAhreadExecutionContext)

  val unit: Async[Unit] = ().async

  def none[A]: Async[Option[A]] = futureNone

  def seq[A]: Async[Seq[A]] = futureSeq

  def nil[A]: Async[List[A]] = futureNil

  def fold[A, S](iter: Iterable[A], s: S)(f: (S, A) => Async[S]): Async[S] = {

    val iterator = iter.iterator

    @tailrec
    def fold(s: S): Async[S] = {
      if (iterator.isEmpty) Async(s)
      else {
        val v = iterator.next()
        f(s, v) match {
          case Succeed(s)        => fold(s)
          case Failed(s)         => Failed(s)
          case v: InCompleted[S] => v.value() match {
            case Some(Success(s)) => fold(s)
            case Some(Failure(s)) => Failed(s)
            case None             => v.flatMap(break)
          }
        }
      }
    }

    def break(s: S) = fold(s)

    fold(s)
  }


  def foldUnit[A](iter: Iterable[Async[A]]): Async[Unit] = fold(iter, ()) { (_, x) => x.unit }


  sealed trait Completed[+A] extends Async[A] { self =>

    def valueTry: Try[A]

    final def value() = Some(valueTry)

    final def mapTry[B](f: Try[A] => Try[B]): Async[B] = safe { Completed(f(valueTry)) }

    final def mapFailure(f: Throwable => Throwable) = flatMapFailure(failure => Failed(f(failure)))

    def future = Future.fromTry(valueTry)

    def onComplete[B](f: Try[A] => B): Unit = safeUnit { f(valueTry) }

    final def await(timeout: Duration) = self
  }

  object Completed {
    def apply[A](value: Try[A]): Completed[A] = {
      value match {
        case Success(value) => Succeed(value)
        case Failure(value) => Failed(value)
      }
    }
  }


  final case class Succeed[A] private(v: A) extends Completed[A] { self =>

    def valueTry = Success(v)

    def foreach[B](f: A => B) = safeUnit { f(v) }

    def map[B](f: A => B) = safe { Succeed(f(v)) }

    def flatMap[B](f: A => Async[B]) = safe { f(v) }

    def flatMapFailure[B >: A](f: Throwable => Async[B]) = this

    def get(timeout: Duration) = v

    def onSuccess[B](f: A => B) = safeUnit { f(v) }

    def onFailure[B](f: Throwable => B) = {}

    def recover[B >: A](pf: PartialFunction[Throwable, B]) = self

    def recoverWith[B >: A](pf: PartialFunction[Throwable, Async[B]]) = self

    def redeem[B](err: Throwable => Async[B], succ: A => Async[B]) = self.flatMap(succ)

    def redeemPure[B](err: Throwable => B, succ: A => B) = self.map(succ)

    override def toString = s"Async($v)"
  }


  final case class Failed private(v: Throwable) extends Completed[Nothing] { self =>

    def valueTry = Failure(v)

    def foreach[B](f: Nothing => B) = {}

    def map[B](f: Nothing => B) = self

    def flatMap[B](f: Nothing => Async[B]) = self

    def flatMapFailure[B >: Nothing](f: Throwable => Async[B]) = safe { f(v) }

    def get(timeout: Duration) = throw v

    def onSuccess[B](f: Nothing => B) = {}

    def onFailure[B](f: Throwable => B) = safeUnit { f(v) }

    def recover[B >: Nothing](pf: PartialFunction[Throwable, B]) = {
      safe { if (pf.isDefinedAt(v)) Succeed(pf(v)) else self }
    }

    def recoverWith[B >: Nothing](pf: PartialFunction[Throwable, Async[B]]) = {
      safe { if (pf.isDefinedAt(v)) pf(v) else self }
    }

    def redeem[B](err: Throwable => Async[B], succ: Nothing => Async[B]) = safe { err(v) }

    def redeemPure[B](err: Throwable => B, succ: Nothing => B) = safe { Succeed(err(v)) }

    override def toString = s"Async($v)"
  }


  final case class InCompleted[A] private(v: Future[A])(implicit val ec: ExecutionContext) extends Async[A] { self =>

    def foreach[B](f: A => B) = {
      v.value match {
        case Some(Success(v)) => safeUnit { f(v) }
        case Some(Failure(v)) =>
        case None             => v.foreach(f)
      }
    }

    def map[B](f: A => B) = {
      v.value match {
        case Some(Success(v)) => safe { Succeed(f(v)) }
        case Some(Failure(v)) => Failed(v)
        case None             => InCompleted(v.map(f))
      }
    }

    def mapTry[B](f: Try[A] => Try[B]): Async[B] = {
      v.value match {
        case Some(v) => safe { Completed(f(v)) }
        case None    => InCompleted(FutureOps(v).transform(f))
      }
    }

    def flatMap[B](f: A => Async[B]) = {
      v.value match {
        case Some(Success(v)) => safe { f(v) }
        case Some(Failure(v)) => Failed(v)
        case None             =>
          val result = for {
            v <- v
            v <- f(v).future
          } yield v
          InCompleted(result)
      }
    }

    def mapFailure(f: Throwable => Throwable) = {
      v.value match {
        case Some(Success(v)) => Succeed(v)
        case Some(Failure(v)) => safe { Failed(f(v)) }
        case None             => InCompleted(v.recover { case v => throw f(v) })
      }
    }

    def flatMapFailure[B >: A](f: Throwable => Async[B]) = {
      v.value match {
        case Some(Success(v)) => Succeed(v)
        case Some(Failure(v)) => safe { f(v) }
        case None             => InCompleted(v.recoverWith { case v => f(v).future })
      }
    }

    def value() = v.value

    def future = v

    def get(timeout: Duration) = Await.result(v, timeout)

    def await(timeout: Duration) = {
      v.value match {
        case Some(Success(v)) => Succeed(v)
        case Some(Failure(v)) => Failed(v)
        case None             => Completed(Try(Await.result(v, timeout)))
      }
    }

    def onComplete[B](f: Try[A] => B): Unit = {
      v.value match {
        case Some(v) => safeUnit { f(v) }
        case None    => future.onComplete(f)
      }
    }

    def onSuccess[B](f: A => B) = onComplete {
      case Success(v) => f(v)
      case Failure(v) =>
    }

    def onFailure[B](f: Throwable => B) = onComplete {
      case Success(v) =>
      case Failure(v) => f(v)
    }

    def recover[B >: A](pf: PartialFunction[Throwable, B]) = {
      v.value match {
        case Some(Success(v)) => Succeed(v)
        case Some(Failure(v)) => safe { if (pf.isDefinedAt(v)) Succeed(pf(v)) else Failed(v) }
        case None             => InCompleted(v.recover(pf))
      }
    }

    def recoverWith[B >: A](pf: PartialFunction[Throwable, Async[B]]) = {
      v.value match {
        case Some(Success(v)) => Succeed(v)
        case Some(Failure(v)) => safe { if (pf.isDefinedAt(v)) pf(v) else Failed(v) }
        case None             => InCompleted(v.recoverWith(pf.andThen(_.future)))
      }
    }

    def redeem[B](err: Throwable => Async[B], succ: A => Async[B]) = {
      v.value match {
        case Some(Success(v)) => safe { succ(v) }
        case Some(Failure(v)) => safe { err(v) }
        case None             =>
          val result = future
            .flatMap(succ(_).future)
            .recoverWith { case v => err(v).future }
          InCompleted(result)
      }
    }

    def redeemPure[B](err: Throwable => B, succ: A => B) = {
      v.value match {
        case Some(Success(v)) => safe { Succeed(succ(v)) }
        case Some(Failure(v)) => safe { Succeed(err(v)) }
        case None             =>
          val result = FutureOps(v).transform {
            case Success(v) => Try(succ(v))
            case Failure(v) => Try(err(v))
          }
          InCompleted(result)
      }
    }

    override def toString = "Async(<not completed>)"
  }


  private def safe[A](f: => Async[A]): Async[A] = try f catch { case NonFatal(failure) => Failed(failure) }

  private def safeUnit[A](f: => A): Unit = try f catch { case NonFatal(_) => }
}


object AsyncConverters {
  implicit class FutureAsync[A](val self: Future[A]) extends AnyVal {
    def async(implicit ec: ExecutionContext): Async[A] = Async(self)
  }

  implicit class AnyAsync[A](val self: A) extends AnyVal {
    def async: Async[A] = Async(self)
  }
}