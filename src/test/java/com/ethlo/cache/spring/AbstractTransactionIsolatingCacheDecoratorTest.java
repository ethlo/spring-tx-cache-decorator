package com.ethlo.cache.spring;

/*-
 * #%L
 * spring-tx-cache-decorator
 * %%
 * Copyright (C) 2018 - 2019 Morten Haraldsen (ethlo)
 * %%
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
 * #L%
 */

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.NullValue;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RunWith(PowerMockRunner.class)
@PrepareForTest(TransactionSynchronizationManager.class)
public abstract class AbstractTransactionIsolatingCacheDecoratorTest
{
    protected ConcurrentMap<Object, Object> realCacheA;
    protected Cache decoratorA;

    protected ConcurrentMap<Object, Object> realCacheB;
    protected Cache decoratorB;

    void mockTxnManager(final boolean active)
    {
        mockStatic(TransactionSynchronizationManager.class);
        when(TransactionSynchronizationManager.isSynchronizationActive()).thenReturn(active);
    }

    @Test
    public void testInvokeOutsideTransaction()
    {
        mockTxnManager(false);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(false);
        decoratorA.put("foo", "bar");
        assertThat(realCacheA).containsEntry("foo", "bar");
    }

    @Test
    public void testGetNativeCache()
    {
        assertThat(decoratorA.getNativeCache()).isNotNull();
    }

    @Test
    public void testGetName()
    {
        assertThat(decoratorA.getName()).isEqualTo("my-cache-a");
        assertThat(decoratorB.getName()).isEqualTo("my-cache-b");
    }

    @Test(expected = IllegalStateException.class)
    public void testPerformUnsafePutIfAbsent()
    {
        mockTxnManager(true);
        decoratorA.putIfAbsent("foo", "bar");
    }

    @Test
    public void testCacheNull()
    {
        mockTxnManager(true);
        decoratorA.put("foo", null);
        assertThat(decoratorA.get("foo")).isNull();
    }

    @Test
    public void testCacheExplicitNull()
    {
        decoratorA.put("foo", NullValue.INSTANCE);
        assertThat(decoratorA.get("foo")).isNull();
    }

    @Test
    public void testCacheNullExisting()
    {
        decoratorA.put("foo", null);

        mockTxnManager(true);
        assertThat(decoratorA.get("foo")).isNull();
    }

    @Test
    public void testInvokePutInsideTransactionWithRollback()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);
        decoratorA.put("foo", "bar");

        // Make sure the transient cache holds this
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");

        // Should not yet be in the cache itself
        assertThat(realCacheA).doesNotContainKeys("foo");

        // Rollback
        mockTransactionEnd(false);

        // Make sure the cache does not hold it
        assertThat(decoratorA.get("foo", String.class)).isNull();
    }

    @Test
    public void testInvokeSecondGetInsideTransaction()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);

        // Cache real cache values avoiding second round-trip to real cache
        decoratorA = new EnhancedTransactionAwareCacheDecorator(new ConcurrentMapCache("my-cache-b", realCacheA, true), true);

        // When value in real cache
        realCacheA.put("foo", "bar");
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");

        // Clear real cache
        realCacheA.clear();
        assertThat(realCacheA).doesNotContainKeys("foo");

        // Make sure the transient cache holds this still (it should be cached in decorator)
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");

        // Commit
        mockTransactionEnd(true);

        // Make sure the cache does not hold it
        assertThat(decoratorA.get("foo", String.class)).isNull();
    }

    @Test(expected = Cache.ValueRetrievalException.class)
    public void testLoadFunctionException()
    {
        decoratorA.get("foo", () -> {
            throw new IOException("Oh noes");
        });
    }

    @Test
    public void testInvokePutInsideTransactionWithCommit()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);
        decoratorA.put("foo", "bar");

        // Make sure the transient cache holds this
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");

        // Should not yet be in the cache itself
        assertThat(realCacheA).doesNotContainKeys("foo");

        // Commit
        mockTransactionEnd(true);

        // Make sure the cache now holds the value
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");
    }

    @Test
    public void testInvokePutInsideTransactionWithCommitMultiple()
    {
        mockTxnManager(true);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isEqualTo(true);
        decoratorA.put("foo", "bar");
        decoratorB.put("foo2", "bar2");

        // Make sure the transient cache holds this
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");
        assertThat(decoratorB.get("foo2", String.class)).isEqualTo("bar2");

        // Should not yet be in the cache itself
        assertThat(realCacheA).isEmpty();
        assertThat(realCacheB).isEmpty();

        // Commit
        mockTransactionEnd(true);

        // Make sure the cache now holds the value
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");
        assertThat(decoratorA.get("foo2", String.class)).isNull();
        assertThat(decoratorB.get("foo2", String.class)).isEqualTo("bar2");
        assertThat(decoratorB.get("foo", String.class)).isNull();
    }

    @Test
    public void testInvokeEvictInsideTransactionWithCommit()
    {
        // Given
        decoratorA.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");
        decoratorA.evict("foo");

        // Then
        // Should still be in the cache itself
        assertThat(realCacheA).containsKey("foo");

        // But should not be available in the cache
        assertThat(decoratorA.get("foo")).isNull();

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(realCacheA).doesNotContainKeys("foo");
        assertThat(decoratorA.get("foo")).isNull();
    }

    @Test
    public void testInvokeEvictOfOldValueAndPuttingNewInsideTransactionIsUpdated()
    {
        decoratorA.put("foo", "bar");

        // Given
        mockTxnManager(true);
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");

        decoratorA.put("foo", "baz");

        assertThat(decoratorA.get("foo").get()).isEqualTo("baz");

        // Commit
        mockTransactionEnd(false);

        // After rollback, should be back to before transaction
        assertThat(realCacheA).containsEntry("foo", "bar");
        assertThat(decoratorA.get("foo").get()).isEqualTo("bar");
    }

    public void testInvokeEvictOfOldValueAndPuttingNewInsideTransactionIsUpdatedOnCommit()
    {
        decoratorA.put("foo", "bar");

        // Given
        mockTxnManager(true);
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");
        decoratorA.evict("foo");

        decoratorA.put("foo", "baz");

        assertThat(decoratorA.get("foo").get()).isEqualTo("baz");

        // Commit
        mockTransactionEnd(true);

        // After commit, should be as before transaction
        assertThat(realCacheA).containsEntry("foo", "baz");
        assertThat(decoratorA.get("foo").get()).isEqualTo("baz");
    }

    @Test
    public void testInvokeClearInsideTransactionWithCommit()
    {
        // Given
        decoratorA.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");
        decoratorA.clear();

        // Then
        // Should still be in the cache itself
        assertThat(realCacheA).containsKey("foo");

        // But should not be available in the cache
        assertThat(decoratorA.get("foo")).isNull();

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(realCacheA).doesNotContainKeys("foo");
        assertThat(decoratorA.get("foo")).isNull();
    }

    @Test
    public void testInvokeClearInsideTransactionWithPutsAndCommit()
    {
        // Given
        decoratorA.put("foo", "bar");

        // When
        mockTxnManager(true);
        assertThat(decoratorA.get("foo", String.class)).isEqualTo("bar");
        decoratorA.clear();

        // Then
        // Should still be in the cache itself
        assertThat(realCacheA).containsKey("foo");

        // But should not be available in the cache
        assertThat(decoratorA.get("foo")).isNull();

        decoratorA.put("para", "bel");

        // Commit
        mockTransactionEnd(true);

        // After commit, should be in neither
        assertThat(realCacheA).doesNotContainKeys("foo");
        assertThat(decoratorA.get("foo")).isNull();

        // But the newly added should be
        assertThat(realCacheA.get("para")).isEqualTo("bel");
    }

    @Test
    public void testInvokeGetWithLoaderClearInsideTransactionWithCommit()
    {
        // Given
        decoratorA.put("foo", "bar");

        // When
        mockTxnManager(true);

        // Already has a value in cache ("bar"), so loader not used
        assertThat(decoratorA.get("foo", () -> "fresh")).isEqualTo("bar");

        decoratorA.clear();

        assertThat(decoratorA.get("foo", () -> "fresh")).isEqualTo("fresh");
    }

    private void mockTransactionEnd(final boolean commit)
    {
        final ArgumentCaptor<TransactionSynchronization> propertiesCaptor = ArgumentCaptor.forClass(TransactionSynchronization.class);
        PowerMockito.verifyStatic(TransactionSynchronizationManager.class);
        TransactionSynchronizationManager.registerSynchronization(propertiesCaptor.capture());
        final TransactionSynchronization passedInValue = propertiesCaptor.getValue();
        if (commit)
        {
            passedInValue.afterCommit();
            passedInValue.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        else
        {
            passedInValue.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }
}
