/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio
package interop

import cats.effect.{ Concurrent, ContextShift, ExitCase }
import cats.{ effect, _ }
import zio.{ clock => zioClock }
import zio.clock.Clock

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS, TimeUnit }

abstract class CatsPlatform extends CatsInstances with CatsZManagedInstances with CatsZManagedSyntax {
  val console = interop.console.cats

  trait CatsApp extends App {
    implicit val runtime: Runtime[Environment] = this
  }

  object implicits {
    implicit def ioTimer[E]: effect.Timer[IO[E, ?]] =
      new effect.Timer[IO[E, ?]] {
        override def clock: effect.Clock[IO[E, ?]] = new effect.Clock[IO[E, ?]] {
          override def monotonic(unit: TimeUnit): IO[E, Long] =
            Clock.Live.clock.nanoTime.map(unit.convert(_, NANOSECONDS))

          override def realTime(unit: TimeUnit): IO[E, Long] =
            Clock.Live.clock.currentTime(unit)
        }

        override def sleep(duration: FiniteDuration): IO[E, Unit] =
          Clock.Live.clock.sleep(zio.duration.Duration.fromNanos(duration.toNanos))
      }
  }
}

abstract class CatsInstances extends CatsInstances1 {

  implicit def zioContextShift[R, E]: ContextShift[ZIO[R, E, ?]] = new ContextShift[ZIO[R, E, ?]] {
    override def shift: ZIO[R, E, Unit] =
      ZIO.yieldNow

    override def evalOn[A](ec: ExecutionContext)(fa: ZIO[R, E, A]): ZIO[R, E, A] =
      fa.on(ec)
  }

  implicit def zioTimer[R <: Clock, E]: effect.Timer[ZIO[R, E, ?]] = new effect.Timer[ZIO[R, E, ?]] {
    override def clock: cats.effect.Clock[ZIO[R, E, ?]] = new effect.Clock[ZIO[R, E, ?]] {
      override def monotonic(unit: TimeUnit): ZIO[R, E, Long] =
        zioClock.nanoTime.map(unit.convert(_, NANOSECONDS))

      override def realTime(unit: TimeUnit): ZIO[R, E, Long] =
        zioClock.currentTime(unit)
    }

    override def sleep(duration: FiniteDuration): ZIO[R, E, Unit] =
      zioClock.sleep(zio.duration.Duration.fromNanos(duration.toNanos))
  }

  implicit def taskEffectInstances[R](
    implicit runtime: Runtime[R]
  ): effect.ConcurrentEffect[RIO[R, ?]] with SemigroupK[RIO[R, ?]] =
    new CatsConcurrentEffect[R](runtime)

}

sealed trait CatsInstances1 extends CatsInstances2 {
  implicit def ioMonoidInstances[R, E: Monoid]
    : MonadError[ZIO[R, E, ?], E] with Bifunctor[ZIO[R, ?, ?]] with Alternative[ZIO[R, E, ?]] =
    new CatsAlternative[R, E] with CatsBifunctor[R]

  implicit def taskConcurrentInstances[R]: effect.Concurrent[RIO[R, ?]] with SemigroupK[RIO[R, ?]] =
    new CatsConcurrent[R]

  implicit def parallelInstance[R, E](implicit M: Monad[ZIO[R, E, ?]]): Parallel[ZIO[R, E, ?], ParIO[R, E, ?]] =
    new CatsParallel[R, E](M)

  implicit def commutativeApplicativeInstance[R, E]: CommutativeApplicative[ParIO[R, E, ?]] =
    new CatsParApplicative[R, E]
}

sealed trait CatsInstances2 {
  implicit def ioInstances[R, E]
    : MonadError[ZIO[R, E, ?], E] with Bifunctor[ZIO[R, ?, ?]] with SemigroupK[ZIO[R, E, ?]] =
    new CatsMonadError[R, E] with CatsBifunctor[R] with CatsSemigroupK[R, E]
}

private class CatsConcurrentEffect[R](rts: Runtime[R])
    extends CatsConcurrent[R]
    with effect.ConcurrentEffect[RIO[R, ?]]
    with effect.Effect[RIO[R, ?]] {

  override final def runAsync[A](
    fa: RIO[R, A]
  )(cb: Either[Throwable, A] => effect.IO[Unit]): effect.SyncIO[Unit] =
    effect.SyncIO {
      rts.unsafeRunAsync(fa) { exit =>
        cb(exitToEither(exit)).unsafeRunAsync(_ => ())
      }
    }

  override final def runCancelable[A](
    fa: RIO[R, A]
  )(cb: Either[Throwable, A] => effect.IO[Unit]): effect.SyncIO[effect.CancelToken[RIO[R, ?]]] =
    effect.SyncIO {
      rts.unsafeRun {
        ZIO.interruptible(fa).fork.flatMap { f =>
          f.await
            .flatMap(exit => IO.effect(cb(exitToEither(exit)).unsafeRunAsync(_ => ())))
            .fork
            .const(f.interrupt.unit)
        }
      }
    }

  override final def toIO[A](fa: RIO[R, A]): effect.IO[A] =
    effect.ConcurrentEffect.toIOFromRunCancelable(fa)(this)
}

private class CatsConcurrent[R] extends CatsEffect[R] with Concurrent[RIO[R, ?]] {

  private[this] final def toFiber[A](f: Fiber[Throwable, A]): effect.Fiber[RIO[R, ?], A] =
    new effect.Fiber[RIO[R, ?], A] {
      override final val cancel: RIO[R, Unit] = f.interrupt.unit

      override final val join: RIO[R, A] = f.join
    }

  override final def liftIO[A](ioa: cats.effect.IO[A]): RIO[R, A] =
    Concurrent.liftIO(ioa)(this)

  override final def cancelable[A](k: (Either[Throwable, A] => Unit) => effect.CancelToken[RIO[R, ?]]): RIO[R, A] =
    ZIO.accessM { r =>
      ZIO.effectAsyncInterrupt { (kk: RIO[R, A] => Unit) =>
        val token: effect.CancelToken[Task] = {
          k(e => kk(eitherToIO(e))).provide(r)
        }

        Left(token.provide(r).orDie)
      }
    }

  override final def race[A, B](fa: RIO[R, A], fb: RIO[R, B]): RIO[R, Either[A, B]] =
    racePair(fa, fb).flatMap {
      case Left((a, fiberB)) =>
        fiberB.cancel.const(Left(a))
      case Right((fiberA, b)) =>
        fiberA.cancel.const(Right(b))
    }

  override final def start[A](fa: RIO[R, A]): RIO[R, effect.Fiber[RIO[R, ?], A]] =
    ZIO.interruptible(fa).fork.map(toFiber)

  override final def racePair[A, B](
    fa: RIO[R, A],
    fb: RIO[R, B]
  ): RIO[R, Either[(A, effect.Fiber[RIO[R, ?], B]), (effect.Fiber[RIO[R, ?], A], B)]] =
    (fa raceWith fb)(
      { case (l, f) => l.fold(f.interrupt *> RIO.halt(_), RIO.succeed).map(lv => Left((lv, toFiber(f)))) },
      { case (r, f) => r.fold(f.interrupt *> RIO.halt(_), RIO.succeed).map(rv => Right((toFiber(f), rv))) }
    )
}

private class CatsEffect[R]
    extends CatsMonadError[R, Throwable]
    with effect.Async[RIO[R, ?]]
    with CatsSemigroupK[R, Throwable] {
  @inline final protected[this] def exitToEither[A](e: Exit[Throwable, A]): Either[Throwable, A] =
    e.fold(_.failures[Throwable] match {
      case t :: Nil => Left(t)
      case _        => e.toEither
    }, Right(_))

  @inline final protected[this] def eitherToIO[A]: Either[Throwable, A] => RIO[R, A] = {
    case Left(t)  => RIO.fail(t)
    case Right(r) => RIO.succeed(r)
  }

  @inline final private[this] def exitToExitCase[A]: Exit[Throwable, A] => ExitCase[Throwable] = {
    case Exit.Success(_)                          => ExitCase.Completed
    case Exit.Failure(cause) if cause.interrupted => ExitCase.Canceled
    case Exit.Failure(cause) =>
      cause.failureOrCause match {
        case Left(t) => ExitCase.Error(t)
        case _       => ExitCase.Error(FiberFailure(cause))
      }
  }

  override final def never[A]: RIO[R, A] =
    RIO.never

  override final def async[A](k: (Either[Throwable, A] => Unit) => Unit): RIO[R, A] =
    RIO.effectAsync { (kk: RIO[R, A] => Unit) =>
      k(e => kk(eitherToIO(e)))
    }

  override final def asyncF[A](k: (Either[Throwable, A] => Unit) => RIO[R, Unit]): RIO[R, A] =
    ZIO.accessM { r =>
      RIO.effectAsyncM { (kk: Task[A] => Unit) =>
        k(e => kk(eitherToIO(e).provide(r))).provide(r).orDie
      }
    }

  override final def suspend[A](thunk: => RIO[R, A]): RIO[R, A] =
    ZIO.flatten(RIO.effect(thunk))

  override final def delay[A](thunk: => A): RIO[R, A] =
    RIO.effect(thunk)

  override final def bracket[A, B](acquire: RIO[R, A])(use: A => RIO[R, B])(
    release: A => RIO[R, Unit]
  ): RIO[R, B] =
    ZIO.bracket(acquire)(release(_).orDie)(use)

  override final def bracketCase[A, B](
    acquire: RIO[R, A]
  )(use: A => RIO[R, B])(release: (A, ExitCase[Throwable]) => RIO[R, Unit]): RIO[R, B] =
    ZIO.bracketExit(acquire) { (a, exit: Exit[Throwable, _]) =>
      val exitCase = exitToExitCase(exit)
      release(a, exitCase).orDie
    }(use)

  override def uncancelable[A](fa: RIO[R, A]): RIO[R, A] =
    fa.uninterruptible

  override final def guarantee[A](fa: RIO[R, A])(finalizer: RIO[R, Unit]): RIO[R, A] =
    ZIO.accessM { r =>
      fa.provide(r).ensuring(finalizer.provide(r).orDie)
    }
}

private class CatsMonad[R, E] extends Monad[ZIO[R, E, ?]] {
  override final def pure[A](a: A): ZIO[R, E, A]                                         = ZIO.succeed(a)
  override final def map[A, B](fa: ZIO[R, E, A])(f: A => B): ZIO[R, E, B]                = fa.map(f)
  override final def flatMap[A, B](fa: ZIO[R, E, A])(f: A => ZIO[R, E, B]): ZIO[R, E, B] = fa.flatMap(f)
  override final def tailRecM[A, B](a: A)(f: A => ZIO[R, E, Either[A, B]]): ZIO[R, E, B] =
    ZIO.effectSuspendTotal(f(a)).flatMap {
      case Left(l)  => tailRecM(l)(f)
      case Right(r) => ZIO.succeed(r)
    }
}

private class CatsMonadError[R, E] extends CatsMonad[R, E] with MonadError[ZIO[R, E, ?], E] {
  override final def handleErrorWith[A](fa: ZIO[R, E, A])(f: E => ZIO[R, E, A]): ZIO[R, E, A] = fa.catchAll(f)
  override final def raiseError[A](e: E): ZIO[R, E, A]                                        = ZIO.fail(e)
}

/** lossy, throws away errors using the "first success" interpretation of SemigroupK */
private trait CatsSemigroupK[R, E] extends SemigroupK[ZIO[R, E, ?]] {
  override final def combineK[A](a: ZIO[R, E, A], b: ZIO[R, E, A]): ZIO[R, E, A] = a.orElse(b)
}

private class CatsAlternative[R, E: Monoid] extends CatsMonadError[R, E] with Alternative[ZIO[R, E, ?]] {
  override final def combineK[A](a: ZIO[R, E, A], b: ZIO[R, E, A]): ZIO[R, E, A] =
    a.catchAll { e1 =>
      b.catchAll { e2 =>
        ZIO.fail(Monoid[E].combine(e1, e2))
      }
    }
  override final def empty[A]: ZIO[R, E, A] = raiseError(Monoid[E].empty)
}

trait CatsBifunctor[R] extends Bifunctor[ZIO[R, ?, ?]] {
  override final def bimap[A, B, C, D](fab: ZIO[R, A, B])(f: A => C, g: B => D): ZIO[R, C, D] =
    fab.bimap(f, g)
}

private class CatsParallel[R, E](final override val monad: Monad[ZIO[R, E, ?]])
    extends Parallel[ZIO[R, E, ?], ParIO[R, E, ?]] {

  final override val applicative: Applicative[ParIO[R, E, ?]] =
    new CatsParApplicative[R, E]

  final override val sequential: ParIO[R, E, ?] ~> ZIO[R, E, ?] =
    new (ParIO[R, E, ?] ~> ZIO[R, E, ?]) { def apply[A](fa: ParIO[R, E, A]): ZIO[R, E, A] = Par.unwrap(fa) }

  final override val parallel: ZIO[R, E, ?] ~> ParIO[R, E, ?] =
    new (ZIO[R, E, ?] ~> ParIO[R, E, ?]) {
      def apply[A](fa: ZIO[R, E, A]): ParIO[R, E, A] = Par(fa)
    }
}

private class CatsParApplicative[R, E] extends CommutativeApplicative[ParIO[R, E, ?]] {

  final override def pure[A](x: A): ParIO[R, E, A] =
    Par(ZIO.succeed(x))

  final override def map2[A, B, Z](fa: ParIO[R, E, A], fb: ParIO[R, E, B])(f: (A, B) => Z): ParIO[R, E, Z] =
    Par(Par.unwrap(fa).zipPar(Par.unwrap(fb)).map(f.tupled))

  final override def ap[A, B](ff: ParIO[R, E, A => B])(fa: ParIO[R, E, A]): ParIO[R, E, B] =
    Par(Par.unwrap(ff).flatMap(Par.unwrap(fa).map))

  final override def product[A, B](fa: ParIO[R, E, A], fb: ParIO[R, E, B]): ParIO[R, E, (A, B)] =
    map2(fa, fb)(_ -> _)

  final override def map[A, B](fa: ParIO[R, E, A])(f: A => B): ParIO[R, E, B] =
    Par(Par.unwrap(fa).map(f))

  final override def unit: ParIO[R, E, Unit] =
    Par(ZIO.unit)
}
