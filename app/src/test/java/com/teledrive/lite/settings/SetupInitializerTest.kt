package com.teledrive.lite.settings

import com.teledrive.lite.crypto.KeyDerivationParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupInitializerTest {
    @Test
    fun initializeCommitsOneGenerationThenWipesCallerAndDerivedBuffers() = runBlocking {
        val stateStore = FakeStatePersistence()
        val parameters = KeyDerivationParameters.pbkdf2(ByteArray(16) { it.toByte() }, 600_000)
        val derivedKey = ByteArray(32) { (it + 1).toByte() }
        val password = "Strong password 123!".toCharArray()
        val initializer = SetupInitializer(
            stateStore = stateStore,
            parametersFactory = { parameters },
            masterKeyDeriver = { _, _ -> derivedKey },
            workDispatcher = Dispatchers.Unconfined,
        )
        val config = ValidatedConnectionConfig("token", -1001234567890)

        initializer.initialize(config, password)

        assertEquals(config, stateStore.config)
        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, stateStore.masterKey)
        assertEquals(parameters.algorithm, stateStore.parameters?.algorithm)
        assertArrayEquals(parameters.salt, stateStore.parameters?.salt)
        assertEquals(1, stateStore.commitCalls)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(derivedKey.all { it == 0.toByte() })
    }

    @Test
    fun derivationOrCommitFailureNeverDeletesExistingGeneration() {
        val oldConfig = ValidatedConnectionConfig("old-token", -1001111111111)
        val oldKey = ByteArray(32) { 7 }
        val oldParameters = KeyDerivationParameters.pbkdf2(ByteArray(16) { 8 }, 600_000)
        val stateStore = FakeStatePersistence().apply {
            config = oldConfig
            masterKey = oldKey.copyOf()
            parameters = oldParameters
        }
        val deriveFailure = SetupInitializer(
            stateStore = stateStore,
            masterKeyDeriver = { _, _ -> throw IllegalStateException("derive failed") },
            workDispatcher = Dispatchers.Unconfined,
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                deriveFailure.initialize(
                    ValidatedConnectionConfig("new-token", -1002222222222),
                    "Strong password 123!".toCharArray(),
                )
            }
        }
        assertEquals(0, stateStore.commitCalls)
        assertEquals(oldConfig, stateStore.config)
        assertArrayEquals(oldKey, stateStore.masterKey)

        stateStore.failCommit = true
        val commitFailure = SetupInitializer(
            stateStore = stateStore,
            masterKeyDeriver = { _, _ -> ByteArray(32) },
            workDispatcher = Dispatchers.Unconfined,
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                commitFailure.initialize(
                    ValidatedConnectionConfig("new-token", -1002222222222),
                    "Strong password 123!".toCharArray(),
                )
            }
        }
        assertEquals(oldConfig, stateStore.config)
        assertArrayEquals(oldKey, stateStore.masterKey)
    }

    @Test
    fun cancellationDuringDerivationNeverCommitsCredentials() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stateStore = FakeStatePersistence()
        val password = "Strong password 123!".toCharArray()
        val initializer = SetupInitializer(
            stateStore = stateStore,
            masterKeyDeriver = { _, _ ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                ByteArray(32)
            },
        )

        val job = launch(Dispatchers.Default) {
            initializer.initialize(
                ValidatedConnectionConfig("new-token", -1002222222222),
                password,
            )
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, stateStore.commitCalls)
        assertNull(stateStore.config)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun concurrentInitializationsNeverDeriveOrCommitAtTheSameTime() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val derivationCalls = AtomicInteger()
        val stateStore = FakeStatePersistence()
        val initializer = SetupInitializer(
            stateStore = stateStore,
            masterKeyDeriver = { _, _ ->
                val call = derivationCalls.incrementAndGet()
                val nowActive = active.incrementAndGet()
                maximumActive.accumulateAndGet(nowActive, ::maxOf)
                if (call == 1) {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                active.decrementAndGet()
                ByteArray(32) { call.toByte() }
            },
        )

        val first = launch(Dispatchers.Default) {
            initializer.initialize(
                ValidatedConnectionConfig("token-1", -1001111111111),
                "Strong password 111!".toCharArray(),
            )
        }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val second = launch(Dispatchers.Default) {
            initializer.initialize(
                ValidatedConnectionConfig("token-2", -1002222222222),
                "Strong password 222!".toCharArray(),
            )
        }
        Thread.sleep(100)
        assertEquals(1, maximumActive.get())
        releaseFirst.countDown()
        first.join()
        second.join()

        assertEquals(1, maximumActive.get())
        assertEquals(2, stateStore.commitCalls)
        assertEquals("token-2", stateStore.config?.botToken)
    }

    private class FakeStatePersistence : SetupStatePersistence {
        var config: ValidatedConnectionConfig? = null
        var masterKey: ByteArray? = null
        var parameters: KeyDerivationParameters? = null
        var commitCalls = 0
        var failCommit = false

        @Synchronized
        override fun commit(
            config: ValidatedConnectionConfig,
            masterKey: ByteArray,
            parameters: KeyDerivationParameters,
        ) {
            commitCalls += 1
            if (failCommit) throw IllegalStateException("commit failed")
            this.config = config
            this.masterKey = masterKey.copyOf()
            this.parameters = parameters
        }
    }
}
