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
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class EnhancedTransactionAwareCacheDecorator implements Cache
{
    private static final Logger logger = LoggerFactory.getLogger(EnhancedTransactionAwareCacheDecorator.class);

    private final Cache cache;
    private final boolean errorOnUnsafe;

    // Important: NOT static as we need one for each cache instance
    private final ThreadLocal<TransientCacheData> transientData = ThreadLocal.withInitial(TransientCacheData::new);
    private final boolean cacheCacheResult;

    public EnhancedTransactionAwareCacheDecorator(final Cache cache)
    {
        this(cache, true, false);
    }

    /**
     *
     * @param cache The cache to delegate to
     * @param cacheCacheResult Whether to cache the result from the delegate cache result until the end of the transaction
     */
    public EnhancedTransactionAwareCacheDecorator(final Cache cache, final boolean cacheCacheResult)
    {
        this(cache, true, cacheCacheResult);
    }

    public EnhancedTransactionAwareCacheDecorator(final Cache cache, final boolean errorOnUnsafe, final boolean cacheCacheResult)
    {
        this.cache = cache;
        this.errorOnUnsafe = errorOnUnsafe;
        this.cacheCacheResult = cacheCacheResult;
    }

    @Override
    public @NonNull String getName()
    {
        return cache.getName();
    }

    @Override
    public @NonNull Object getNativeCache()
    {
        return cache.getNativeCache();
    }

    @Override
    @Nullable
    public ValueWrapper get(@NonNull final Object key)
    {
        final TransientCacheData tcd = transientData.get();
        final ValueWrapper res = tcd.getTransientCache().get(key);
        if (res != null && isNull(res))
        {
            // Was explicitly set as deleted
            return null;
        }
        else if (res != null)
        {
            return res;
        }
        else if (!tcd.isCacheCleared())
        {
            logger.debug("Fetching {} from cache", key);
            final ValueWrapper fromRealCache = cache.get(key);

            final ValueWrapper result = Optional.ofNullable(fromRealCache).map(ValueWrapper::get).map(ReadOnlyValueWrapper::new).filter(v -> !isNull(v)).orElse(new ReadOnlyValueWrapper(null));

            if (cacheCacheResult)
            {
                try
                {
                    // Avoid the underlying cache being utilized again for this transaction
                    tcd.getTransientCache().put(key, result);
                } finally
                {
                    cacheSync();
                }
            }

            return isNull(result) ? null : result;
        }

        // Cleared
        return null;
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
        final ValueWrapper res = get(key);
        if (res != null)
        {
            return (T) res.get();
        }

        final T storedData = (T) new LoadFunction(valueLoader).apply(key);
        final TransientCacheData tcd = transientData.get();

        try
        {
            tcd.getTransientCache().put(key, new SimpleValueWrapper(storedData));
        } finally
        {
            cacheSync();
        }

        return storedData;
    }

    private void cacheSync()
    {
        final TransientCacheData tcd = transientData.get();
        if (tcd.isSyncSetup())
        {
            logger.debug("Cache sync transaction already set up. Skipping");
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter()
            {
                @Override
                public void afterCommit()
                {
                    copyTransient();
                }

                @Override
                public void afterCompletion(final int status)
                {
                    transientData.remove();
                    logger.debug("Transaction completed. Cleared transient data");
                }
            });
            tcd.syncSetup();
            logger.debug("Cache sync transaction callback added");
        }
        else
        {
            copyTransient();
        }
    }

    private boolean isNull(ValueWrapper wrapper)
    {
        return wrapper == null || (wrapper.get() == null || wrapper.get() == NullValue.INSTANCE);
    }

    private void copyTransient()
    {
        final TransientCacheData tcd = transientData.get();

        if (tcd.isCacheCleared())
        {
            cache.clear();
        }

        for (Map.Entry<Object, ValueWrapper> entry : tcd.getTransientCache().entrySet())
        {
            final Object key = entry.getKey();
            final ValueWrapper valueWrapper = entry.getValue();
            final Object value = valueWrapper.get();
            if (value == NullValue.INSTANCE)
            {
                logger.debug("Evicting {} from cache {}", key, getName());
                cache.evict(key);
            }
            else if (!(valueWrapper instanceof ReadOnlyValueWrapper))
            {
                logger.debug("Setting {}={} in cache {}", key, value, getName());
                cache.put(key, value);
            }
        }
        tcd.getTransientCache().clear();
    }

    @Override
    public void put(final @NonNull Object key, final Object value)
    {
        final TransientCacheData tcd = transientData.get();
        try
        {
            tcd.getTransientCache().put(key, new SimpleValueWrapper(value));
        } finally
        {
            cacheSync();
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
        return cache.putIfAbsent(key, value);
    }

    @Override
    public void evict(final @NonNull Object key)
    {
        final TransientCacheData tcd = transientData.get();
        try
        {
            tcd.getTransientCache().put(key, new SimpleValueWrapper(NullValue.INSTANCE));
        } finally
        {
            cacheSync();
        }
    }

    @Override
    public void clear()
    {
        final TransientCacheData tcd = transientData.get();
        try
        {
            tcd.cleared();
            tcd.getTransientCache().clear();
        } finally
        {
            cacheSync();
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
