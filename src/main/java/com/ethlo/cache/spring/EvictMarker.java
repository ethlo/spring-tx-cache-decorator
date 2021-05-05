package com.ethlo.cache.spring;

import org.springframework.cache.Cache;

public class EvictMarker implements Cache.ValueWrapper
{
    public static EvictMarker INSTANCE = new EvictMarker();

    private EvictMarker()
    {
    }

    @Override
    public Object get()
    {
        return null;
    }
}
