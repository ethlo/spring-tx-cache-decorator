package com.etho.cache.spring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cache.Cache;

public class TransientCacheData
{
    private final Map<Object, Cache.ValueWrapper> transientCache = new ConcurrentHashMap<>();
    ;
    private boolean hasSyncSetup = false;
    private boolean isCleared = false;

    public boolean isSyncSetup()
    {
        return hasSyncSetup;
    }

    public void syncSetup()
    {
        this.hasSyncSetup = true;
    }

    public boolean isCacheCleared()
    {
        return isCleared;
    }

    public void cleared()
    {
        isCleared = true;
    }

    public Map<Object, Cache.ValueWrapper> getTransientCache()
    {
        return this.transientCache;
    }
}
