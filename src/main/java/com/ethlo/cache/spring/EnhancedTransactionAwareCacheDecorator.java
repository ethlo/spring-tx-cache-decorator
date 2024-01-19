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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

public class EnhancedTransactionAwareCacheDecorator extends TransactionAwareCacheDecorator
{
    private static final Logger logger = LoggerFactory.getLogger(EnhancedTransactionAwareCacheDecorator.class);
    private static final ThreadLocal<TransactionCacheState> transientData = ThreadLocal.withInitial(TransactionCacheState::new);
    private final boolean errorOnUnsafe;
    private final boolean cacheCacheResult;

    public EnhancedTransactionAwareCacheDecorator(final Cache cache)
    {
        this(cache, true, false);
    }

    /**
     * @param cache            The cache to delegate to
     * @param cacheCacheResult Whether to cache the result from the delegate cache result until the end of the transaction
     */
    public EnhancedTransactionAwareCacheDecorator(final Cache cache, final boolean cacheCacheResult)
    {
        this(cache, true, cacheCacheResult);
    }

    public EnhancedTransactionAwareCacheDecorator(final Cache cache, final boolean errorOnUnsafe, final boolean cacheCacheResult)
    {
        super(cache);
        this.errorOnUnsafe = errorOnUnsafe;
        this.cacheCacheResult = cacheCacheResult;
    }

    /**
     * Removes any transient data without any attempt to synchronize to delegate cache(s)
     */
    public static void reset()
    {
        transientData.remove();
    }

    private static void setupSyncToDelegateCaches()
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            if (!transientData.get().isSyncSetup())
            {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter()
                {
                    @Override
                    public void afterCommit()
                    {
                        logger.debug("Transaction commit. Copying to delegate cache");
                        copyTransientToDelegateCaches();
                        Assert.isTrue(!EnhancedTransactionAwareCacheDecorator.isDirty(), "Should have no dirty caches after sync");
                    }

                    @Override
                    public void afterCompletion(final int status)
                    {
                        transientData.remove();
                        logger.debug("Transaction completed. Cleared transient data");
                    }
                });
                transientData.get().setupCacheSynchronizationCallback();
                logger.debug("Transaction synchronization callback added");
            }
        }
    }

    private static boolean isNull(ValueWrapper wrapper)
    {
        return wrapper == null || wrapper.get() == null;
    }

    private static void copyTransientToDelegateCaches()
    {
        final TransactionCacheState transientCacheState = transientData.get();
        transientCacheState.transientCaches().forEach((name, tcd) ->
        {
            final Cache cache = tcd.getDelegate();
            if (tcd.isCleared())
            {
                cache.clear();
            }

            for (Map.Entry<Object, ValueWrapper> entry : tcd.getTransientCache().entrySet())
            {
                final Object key = entry.getKey();
                final ValueWrapper valueWrapper = entry.getValue();
                final Object value = valueWrapper.get();
                if (valueWrapper == EvictMarker.INSTANCE)
                {
                    logger.debug("Evicting {} from delegate cache {}", key, cache.getName());
                    cache.evict(key);
                }
                else if (!(valueWrapper instanceof ReadOnlyValueWrapper))
                {
                    logger.debug("Setting {}={} in delegate cache {}", key, value, cache.getName());
                    cache.put(key, value);
                }
            }

            tcd.getTransientCache().clear();
        });
    }

    public static boolean isDirty()
    {
        return transientData.get().transientCaches().values().stream().anyMatch(TransientCacheData::isDirty);
    }

    @Override
    @Nullable
    public ValueWrapper get(@NonNull final Object key)
    {
    	if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    		return getTargetCache().get(key);
    	}
        final TransientCacheData tcd = tcd();
        final ValueWrapper res = tcd.getTransientCache().get(key);
        if (res == EvictMarker.INSTANCE)
        {
            // Was explicitly set as evicted
            return null;
        }
        else if (res != null)
        {
            return res;
        }
        else if (!tcd.isCleared())
        {
            logger.debug("Fetching {} from delegate cache {}", key, getTargetCache().getName());
            final ValueWrapper fromRealCache = getTargetCache().get(key);
            final ValueWrapper result = Optional.ofNullable(fromRealCache).map(ValueWrapper::get).map(ReadOnlyValueWrapper::new).filter(v -> !isNull(v)).orElse(new ReadOnlyValueWrapper(null));

            if (cacheCacheResult)
            {
                try
                {
                    // Avoid the delegate cache being utilized again for this transaction
                    tcd.getTransientCache().put(key, result);
                } finally
                {
                    setupSyncToDelegateCaches();
                }
            }

            return fromRealCache;
        }

        // Cleared
        return null;
    }

    private TransientCacheData tcd()
    {
        logger.trace("Getting transient cache for {}", getTargetCache().getName());
        return transientData.get().get(getTargetCache());
    }

    @Override
    @Nullable
    public <T> T get(final @NonNull Object key, final Class<T> type)
    {
        return (T) Optional.ofNullable(get(key)).map(ValueWrapper::get).orElse(null);
    }

    @Override
    @Nullable
    public <T> T get(final @NonNull Object key, final @NonNull Callable<T> valueLoader)
    {
    	if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    		return getTargetCache().get(key, valueLoader);
    	}
        final ValueWrapper res = get(key);
        if (res != null)
        {
            return (T) res.get();
        }

        final T storedData = (T) new LoadFunction(valueLoader).apply(key);
        final TransientCacheData tcd = tcd();

        try
        {
            tcd.getTransientCache().put(key, new SimpleValueWrapper(storedData));
        } finally
        {
            setupSyncToDelegateCaches();
        }

        return storedData;
    }

    @Override
    public void put(final @NonNull Object key, final Object value)
    {
    	if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    		getTargetCache().put(key, value);
    		return;
    	}
        final TransientCacheData tcd = tcd();
        try
        {
            tcd.getTransientCache().put(key, new SimpleValueWrapper(value));
        } finally
        {
            setupSyncToDelegateCaches();
        }
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(final @NonNull Object key, final Object value)
    {
        if (errorOnUnsafe)
        {
            throw new IllegalStateException("Not allowed to be used in a transactional mode");
        }
        return getTargetCache().putIfAbsent(key, value);
    }

    @Override
    public void evict(final @NonNull Object key)
    {
    	if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    		getTargetCache().evict(key);
    		return;
    	}
        final TransientCacheData tcd = tcd();
        try
        {
            tcd.getTransientCache().put(key, EvictMarker.INSTANCE);
        } finally
        {
            setupSyncToDelegateCaches();
        }
    }

    @Override
    public void clear()
    {
    	if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    		getTargetCache().clear();
    		return;
    	}
        final TransientCacheData tcd = tcd();
        try
        {
            tcd.setCleared();
            tcd.getTransientCache().clear();
        } finally
        {
            setupSyncToDelegateCaches();
        }
    }

    private static class LoadFunction implements Function<Object, Object>
    {
        private final Callable<?> valueLoader;

        private LoadFunction(Callable<?> valueLoader)
        {
            this.valueLoader = valueLoader;
        }

        @Override
        public Object apply(Object o)
        {
            try
            {
                return this.valueLoader.call();
            }
            catch (Exception ex)
            {
                throw new ValueRetrievalException(o, this.valueLoader, ex);
            }
        }
    }
}
