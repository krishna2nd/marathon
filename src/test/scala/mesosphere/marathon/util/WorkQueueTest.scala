package mesosphere.marathon
package util

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

import mesosphere.UnitTest

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

class WorkQueueTest extends UnitTest {
  "WorkQueue" should {
    "cap the maximum number of concurrent operations" in {
      val counter = new AtomicInteger(0)
      val waitToExit1 = new Semaphore(0)
      val exited1 = new Semaphore(0)
      val waitToExit2 = new Semaphore(0)
      val exited2 = new Semaphore(0)

      val queue = WorkQueue("test", maxConcurrent = 1, maxQueueLength = Int.MaxValue)
      queue.blocking {
        waitToExit1.acquire()
        exited1.release()
      }

      queue.blocking {
        counter.incrementAndGet()
        waitToExit2.acquire()
        exited2.release()
      }

      waitToExit1.release()
      counter.get() should equal(0)
      exited1.acquire()

      waitToExit2.release()
      exited2.acquire()
      counter.get() should equal(1)
    }
    "complete the future with a failure if the queue is capped" in {
      val queue = WorkQueue("abc", maxConcurrent = 1, maxQueueLength = 0)
      val semaphore = new Semaphore(0)
      queue.blocking {
        semaphore.acquire()
      }

      intercept[IllegalStateException] {
        throw queue.blocking {
          semaphore.acquire()
        }.failed.futureValue
      }

    }
    "continue executing even when the previous job failed" in {
      val queue = WorkQueue("failures", 1, Int.MaxValue)
      queue.blocking {
        throw new Exception("Expected")
      }.failed.futureValue.getMessage should equal("Expected")
      queue.blocking {
        7
      }.futureValue should be(7)
    }
    "defer to the parent queue when defined" in {
      val parent = new WorkQueue("parent", 1, 1) {
        override def apply[T](f: => Future[T])(implicit ctx: ExecutionContext): Future[T] =
          Future.successful[T](1.asInstanceOf[T])
      }
      val queue = WorkQueue("child", 1, 1, Some(parent))
      queue.blocking {
        7
      }.futureValue should equal(1)
    }
  }
  "KeyedLock" should {
    "allow exactly one work item per key" in {
      val lock = KeyedLock[String]("abc", Int.MaxValue)
      val sem = new Semaphore(0)
      val counter = new AtomicInteger(0)
      lock.blocking("1") {
        sem.acquire()
      }
      val blocked = lock.blocking("1") {
        counter.incrementAndGet()
      }
      counter.get() should equal(0)
      blocked.isReadyWithin(1.millis) should be(false)
      sem.release()
      blocked.futureValue should be(1)
      counter.get() should equal(1)
    }
    "allow two work items on different keys" in {
      val lock = KeyedLock[String]("abc", Int.MaxValue)
      val sem = new Semaphore(0)
      lock.blocking("1") {
        sem.acquire()
      }
      lock.blocking("2") {
        "done"
      }.futureValue should equal("done")
      sem.release()
    }
  }
}
