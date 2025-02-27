/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.room

import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import androidx.kruth.assertThat
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.filters.SmallTest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.fail
import org.junit.Test

@SmallTest
class CoroutineRoomCancellationTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = StandardTestDispatcher()
    @OptIn(ExperimentalCoroutinesApi::class)
    val testScope = TestScope(testDispatcher)

    private val database = TestDatabase()

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun testSuspend_cancellable_duringLongQuery() = runBlocking {
        database.backingFieldMap["QueryDispatcher"] = Dispatchers.IO

        val inQueryLatch = CountDownLatch(1)
        val cancelledLatch = CountDownLatch(1)

        val cancellationSignal = CancellationSignal()
        cancellationSignal.setOnCancelListener {
            // query was cancelled so now we can finish our test
            cancelledLatch.countDown()
        }

        val job = GlobalScope.launch(Dispatchers.IO) {
            CoroutinesRoom.execute(
                db = database,
                inTransaction = false,
                cancellationSignal = cancellationSignal,
                callable = Callable {
                    // we're triggering our fake query
                    inQueryLatch.countDown()
                    // fake a long query so we can cancel
                    cancelledLatch.await()
                }
            )
        }
        inQueryLatch.await()
        // we're in the query so we can cancel
        job.cancelAndJoin()

        assertThat(cancellationSignal.isCanceled).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun testSuspend_cancellable_beforeQueryStarts() = runBlocking {
        database.backingFieldMap["QueryDispatcher"] = testDispatcher

        val inCoroutineLatch = CountDownLatch(1)
        val cancelledLatch = CountDownLatch(1)

        val cancellationSignal = CancellationSignal()
        cancellationSignal.setOnCancelListener {
            // query was cancelled so now we can finish our test
            cancelledLatch.countDown()
        }

        val job = GlobalScope.launch(Dispatchers.IO) {
            // Coroutine started so now we can cancel it
            inCoroutineLatch.countDown()

            CoroutinesRoom.execute(
                db = database,
                inTransaction = false,
                cancellationSignal = cancellationSignal,
                callable = Callable {
                    // this should never execute
                    fail("Blocking query triggered")
                }
            )
        }
        inCoroutineLatch.await()
        job.cancelAndJoin()
        testDispatcher.scheduler.runCurrent()

        assertThat(cancellationSignal.isCanceled).isTrue()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun testSuspend_exception_in_query() = runBlocking {
        database.backingFieldMap["QueryDispatcher"] = Dispatchers.IO
        val cancellationSignal = CancellationSignal()

        GlobalScope.launch(Dispatchers.IO) {
            try {
                CoroutinesRoom.execute(
                    db = database,
                    inTransaction = false,
                    cancellationSignal = cancellationSignal,
                    callable = Callable {
                        throw SQLiteException("stuff happened")
                    }
                )
            } catch (exception: Throwable) {
                assertThat(exception).isInstanceOf<SQLiteException>()
            }
        }

        assertThat(cancellationSignal.isCanceled).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSuspend_notCancelled() = runBlocking {
        database.backingFieldMap["QueryDispatcher"] = testDispatcher

        val cancellationSignal = CancellationSignal()

        val job = testScope.launch {
            CoroutinesRoom.execute(
                db = database,
                inTransaction = false,
                cancellationSignal = cancellationSignal,
                callable = Callable { /* nothing to do */ }
            )
        }
        testScope.runCurrent()
        // wait for the job to be finished
        job.join()

        assertThat(cancellationSignal.isCanceled).isFalse()
    }

    private class TestDatabase : RoomDatabase() {

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper {
            throw UnsupportedOperationException("Shouldn't be called!")
        }

        override fun createInvalidationTracker(): InvalidationTracker {
            return TestInvalidationTracker(this)
        }

        override fun clearAllTables() {
            throw UnsupportedOperationException("Shouldn't be called!")
        }
    }

    private class TestInvalidationTracker(db: RoomDatabase) : InvalidationTracker(db, "") {
        val observers = mutableListOf<Observer>()

        override fun addObserver(observer: Observer) {
            observers.add(observer)
        }

        override fun removeObserver(observer: Observer) {
            observers.remove(observer)
        }
    }
}
