package com.etho.cache.spring;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class TransactionIsolatingCacheDecorator implements Cache
{
    private static final Logger logger = LoggerFactory.getLogger(TransactionIsolatingCacheDecorator.class);

    private final Cache cache;
    private final boolean errorOnUnsafe;
    private final boolean allowNullValues;

    // Important: NOT static as we need one for each cache instance
    private final ThreadLocal<Map<Object, ValueWrapper>> transientCache = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Boolean> transientCleared = ThreadLocal.withInitial(()->Boolean.FALSE);
    private final ThreadLocal<Boolean> hasSyncSetup = ThreadLocal.withInitial(()->Boolean.FALSE);

    public TransactionIsolatingCacheDecorator(final Cache cache)
    {
        this(cache, true, true);
    }

    public TransactionIsolatingCacheDecorator(final Cache cache, final boolean errorOnUnsafe, final boolean allowNullValues)
    {
        this.cache = cache;
        this.errorOnUnsafe = errorOnUnsafe;
        this.allowNullValues = allowNullValues;
    }

    @Override
    public String getName()
    {
        return cache.getName();
    }

    @Override
    public Object getNativeCache()
    {
        return cache.getNativeCache();
    }

    @Override
    @Nullable
    public ValueWrapper get(final Object key)
    {
        final ValueWrapper res = transientCache.get().get(key);
        if (res != null && NullValue.INSTANCE == res.get())
        {
            // Explicitly set as deleted
            return null;
        }
        else if (res != null)
        {
            return res;
        }
        else if (!transientCleared.get())
        {
            logger.info("Fetching {} from cache", key);
            return cache.get(key);
        }

        // Cleared
        return null;
    }

    @Override
    @Nullable
    public <T> T get(final Object key, final Class<T> type)
    {
        return Optional.ofNullable(get(key)).map(ValueWrapper::get).map(this::fromStoreValue).map(type::cast).orElse(null);
    }

    @Override
    @Nullable
    public <T> T get(final Object key, final Callable<T> valueLoader)
    {
        final ValueWrapper transientData = transientCache.get().get(key);
        if (transientData != null)
        {
            return (T) fromStoreValue(transientData.get());
        }

        final ValueWrapper cachedData = cache.get(key);
        if (cachedData != null)
        {
            return (T) cachedData;
        }

        final T storedData = (T) toStoreValue(new LoadFunction(valueLoader).apply(key));
        transientCache.get().put(key, new SimpleValueWrapper(storedData));

        cacheSync();

        return storedData;
    }

    private void cacheSync()
    {
        if (hasSyncSetup.get())
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
                    transientCache.remove();
                    transientCleared.remove();
                    hasSyncSetup.remove();
                }
            });
        }
        else
        {
            copyTransient();
        }

        logger.info("Cache sync transaction callback added");
        hasSyncSetup.set(true);
    }

    private void copyTransient()
    {
        if (transientCleared.get())
        {
            cache.clear();
        }

        for (Map.Entry<Object, ValueWrapper> entry : transientCache.get().entrySet())
        {
            if (entry.getValue().get() == NullValue.INSTANCE)
            {
                logger.info("Evicting {} from cache {}", entry.getKey(), getName());
                cache.evict(entry.getKey());
            }
            else
            {
                logger.info("Setting {}={} in cache {}", entry.getKey(), entry.getValue().get(), getName());
                cache.put(entry.getKey(), entry.getValue().get());
            }
        }
        transientCache.get().clear();
    }

    @Override
    public void put(final Object key, final Object value)
    {
        transientCache.get().put(key, new SimpleValueWrapper(toStoreValue(value)));
        cacheSync();
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(final Object key, final Object value)
    {
        if (errorOnUnsafe)
        {
            throw new IllegalStateException("Not allowed to be used in a transactional mode");
        }
        return cache.putIfAbsent(key, value);
    }

    @Override
    public void evict(final Object key)
    {
        transientCache.get().put(key, new SimpleValueWrapper(NullValue.INSTANCE));
        cacheSync();
    }

    @Override
    public void clear()
    {
        transientCleared.set(true);
        transientCache.get().clear();
        cacheSync();
    }

    private class LoadFunction implements Function<Object, Object>
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
                return toStoreValue(this.valueLoader.call());
            }
            catch (Exception ex)
            {
                throw new ValueRetrievalException(o, this.valueLoader, ex);
            }
        }
    }

    private Object toStoreValue(@Nullable Object userValue)
    {
        if (userValue == null)
        {
            if (this.allowNullValues)
            {
                return NullValue.INSTANCE;
            }
            throw new IllegalArgumentException(
                    "Cache '" + getName() + "' is configured to not allow null values but null was provided");
        }
        return userValue;
    }

    private Object fromStoreValue(@Nullable Object storeValue)
    {
        if (this.allowNullValues && storeValue == NullValue.INSTANCE)
        {
            return null;
        }
        return storeValue;
    }
}