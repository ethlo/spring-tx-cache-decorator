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

import java.util.concurrent.ConcurrentHashMap;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.etho.cache.spring.TransactionIsolatingCacheDecorator;

@Ignore("This test is hre to illustrate the issues with the default Spring TransactionAwareCacheDecorator")
@RunWith(PowerMockRunner.class)
@PrepareForTest(TransactionSynchronizationManager.class)
public class DefaultSpringTransactionAwareDecoratorTest extends AbstractTransactionIsolatingCacheDecoratorTest
{
    @Before
    public void setup()
    {
        cacheMap = new ConcurrentHashMap<>();
        cache = new TransactionAwareCacheDecorator(new ConcurrentMapCache("my-cache", cacheMap, true));
    }
}
