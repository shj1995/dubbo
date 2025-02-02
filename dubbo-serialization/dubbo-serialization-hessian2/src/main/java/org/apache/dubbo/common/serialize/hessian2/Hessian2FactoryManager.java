/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.serialize.hessian2;

import org.apache.dubbo.common.utils.ConcurrentHashMapUtils;
import org.apache.dubbo.common.utils.DefaultSerializeClassChecker;
import org.apache.dubbo.common.utils.SerializeCheckStatus;
import org.apache.dubbo.common.utils.SerializeSecurityManager;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.model.FrameworkModel;

import com.alibaba.com.caucho.hessian.io.SerializerFactory;

import java.util.concurrent.ConcurrentHashMap;


public class Hessian2FactoryManager {
    String WHITELIST = "dubbo.application.hessian2.whitelist";
    String ALLOW = "dubbo.application.hessian2.allow";
    String DENY = "dubbo.application.hessian2.deny";
    private volatile SerializerFactory SYSTEM_SERIALIZER_FACTORY;
    private volatile SerializerFactory stickySerializerFactory = null;
    private final ConcurrentHashMap<ClassLoader, SerializerFactory> CL_2_SERIALIZER_FACTORY = new ConcurrentHashMap<>();

    private final SerializeSecurityManager serializeSecurityManager;
    private final DefaultSerializeClassChecker defaultSerializeClassChecker;

    public Hessian2FactoryManager(FrameworkModel frameworkModel) {
        serializeSecurityManager = frameworkModel.getBeanFactory().getOrRegisterBean(SerializeSecurityManager.class);
        defaultSerializeClassChecker = frameworkModel.getBeanFactory().getOrRegisterBean(DefaultSerializeClassChecker.class);
    }

    public SerializerFactory getSerializerFactory(ClassLoader classLoader) {
        SerializerFactory sticky = stickySerializerFactory;
        if (sticky != null && sticky.getClassLoader().equals(classLoader)) {
            return sticky;
        }

        if (classLoader == null) {
            // system classloader
            if (SYSTEM_SERIALIZER_FACTORY == null) {
                synchronized (this) {
                    if (SYSTEM_SERIALIZER_FACTORY == null) {
                        SYSTEM_SERIALIZER_FACTORY = createSerializerFactory(null);
                    }
                }
            }
            stickySerializerFactory = SYSTEM_SERIALIZER_FACTORY;
            return SYSTEM_SERIALIZER_FACTORY;
        }

        SerializerFactory factory = ConcurrentHashMapUtils.computeIfAbsent(CL_2_SERIALIZER_FACTORY,
            classLoader, this::createSerializerFactory);
        stickySerializerFactory = factory;
        return factory;
    }

    private SerializerFactory createSerializerFactory(ClassLoader classLoader) {
        String whitelist = System.getProperty(WHITELIST);
        if (StringUtils.isNotEmpty(whitelist)) {
            return createWhiteListSerializerFactory(classLoader);
        }

        return createDefaultSerializerFactory(classLoader);
    }

    private SerializerFactory createDefaultSerializerFactory(ClassLoader classLoader) {
        Hessian2SerializerFactory hessian2SerializerFactory = new Hessian2SerializerFactory(classLoader, defaultSerializeClassChecker);
        hessian2SerializerFactory.setAllowNonSerializable(Boolean.parseBoolean(System.getProperty("dubbo.hessian.allowNonSerializable", "false")));
        hessian2SerializerFactory.getClassFactory().allow("org.apache.dubbo.*");
        return hessian2SerializerFactory;
    }

    public SerializerFactory createWhiteListSerializerFactory(ClassLoader classLoader) {
        SerializerFactory serializerFactory = new Hessian2SerializerFactory(classLoader, defaultSerializeClassChecker);
        String whiteList = System.getProperty(WHITELIST);
        if ("true".equals(whiteList)) {
            serializerFactory.getClassFactory().setWhitelist(true);
            String allowPattern = System.getProperty(ALLOW);
            if (StringUtils.isNotEmpty(allowPattern)) {
                for (String pattern : allowPattern.split(";")) {
                    serializerFactory.getClassFactory().allow(pattern);
                    serializeSecurityManager.addToAlwaysAllowed(pattern);
                }
            }
            serializeSecurityManager.setCheckStatus(SerializeCheckStatus.STRICT);
        } else {
            serializerFactory.getClassFactory().setWhitelist(false);
            String denyPattern = System.getProperty(DENY);
            if (StringUtils.isNotEmpty(denyPattern)) {
                for (String pattern : denyPattern.split(";")) {
                    serializerFactory.getClassFactory().deny(pattern);
                    serializeSecurityManager.addToDisAllowed(pattern);
                }
            }
        }
        serializerFactory.setAllowNonSerializable(Boolean.parseBoolean(System.getProperty("dubbo.hessian.allowNonSerializable", "false")));
        serializerFactory.getClassFactory().allow("org.apache.dubbo.*");
        return serializerFactory;
    }

    public void onRemoveClassLoader(ClassLoader classLoader) {
        CL_2_SERIALIZER_FACTORY.remove(classLoader);
    }
}
