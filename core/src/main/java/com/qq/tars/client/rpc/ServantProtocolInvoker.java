/**
 * Tencent is pleased to support the open source community by making Tars available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.qq.tars.client.rpc;

import com.qq.tars.client.ServantProxyConfig;
import com.qq.tars.client.support.ClientPoolManager;
import com.qq.tars.client.util.ParseTools;
import com.qq.tars.common.util.Constants;
import com.qq.tars.net.core.nio.SelectorManager;
import com.qq.tars.net.protocol.ProtocolFactory;
import com.qq.tars.rpc.common.Invoker;
import com.qq.tars.rpc.common.ProtocolInvoker;
import com.qq.tars.rpc.common.Url;
import com.qq.tars.rpc.common.util.concurrent.ConcurrentHashSet;
import com.qq.tars.rpc.exc.ClientException;
import com.qq.tars.support.log.LoggerFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

public abstract class ServantProtocolInvoker<T> implements ProtocolInvoker<T> {
    private static final Logger logger = LoggerFactory.getClientLogger();
    protected final Class<T> api;
    protected final ServantProxyConfig servantProxyConfig;
    protected final ThreadPoolExecutor threadPoolExecutor;
    protected final ProtocolFactory protocolFactory;
    protected volatile SelectorManager selectorManager = null;
    protected volatile ConcurrentHashSet<Invoker<T>> allInvoker = new ConcurrentHashSet<>();

    public ServantProtocolInvoker(Class<T> api, ServantProxyConfig config, ProtocolFactory protocolFactory,
                                  ThreadPoolExecutor threadPoolExecutor) {
        this.api = api;
        this.servantProxyConfig = config;
        this.threadPoolExecutor = threadPoolExecutor;
        this.protocolFactory = protocolFactory;
        this.allInvoker = this.initInvoker();
    }

    public abstract Invoker<T> create(Class<T> api, Url url) throws Exception;

    public Collection<Invoker<T>> getInvokers() {
        return Collections.unmodifiableCollection(allInvoker);
    }

    public void destroy() {
        destroy(allInvoker);
        this.selectorManager.stop();
    }

    public void refresh() {
        logger.info("try to refresh " + servantProxyConfig.getSimpleObjectName());
        final List<Invoker<T>> invokers = new ArrayList<>(allInvoker);//copy invoker for destroy
        this.allInvoker = this.initInvoker();
        destroy(invokers);
    }

    protected ServantClient[] getClients(Url url) throws IOException {
        int connections = url.getParameter(Constants.TARS_CLIENT_CONNECTIONS, Constants.default_connections);
        ServantClient[] clients = new ServantClient[connections];
        for (int i = 0; i < clients.length; i++) {
            clients[i] = initClient(url);
        }
        return clients;
    }

    protected ServantClient initClient(Url url) {
        ServantClient client = null;
        try {
            boolean tcpNoDelay = url.getParameter(Constants.TARS_CLIENT_TCPNODELAY, false);
            long connectTimeout = url.getParameter(Constants.TARS_CLIENT_CONNECTTIMEOUT, Constants.default_connect_timeout);
            long syncTimeout = url.getParameter(Constants.TARS_CLIENT_SYNCTIMEOUT, Constants.default_sync_timeout);
            long asyncTimeout = url.getParameter(Constants.TARS_CLIENT_ASYNCTIMEOUT, Constants.default_async_timeout);
            boolean udpMode = url.getParameter(Constants.TARS_CLIENT_UDPMODE, false);

            if (this.selectorManager == null) {
                this.selectorManager = ClientPoolManager.getSelectorManager(this.protocolFactory, this.threadPoolExecutor, true, udpMode, this.servantProxyConfig);
            }

            client = new ServantClient(url.getHost(), url.getPort(), this.selectorManager, udpMode);
            client.setConnectTimeout(connectTimeout);
            client.setSyncTimeout(syncTimeout);
            client.setAsyncTimeout(asyncTimeout);
            client.setTcpNoDelay(tcpNoDelay);
        } catch (Throwable e) {
            throw new ClientException(servantProxyConfig.getSimpleObjectName(), "Fail to create client|" + url.toIdentityString() + "|" + e.getLocalizedMessage(), e);
        }
        return client;
    }

    protected ConcurrentHashSet<Invoker<T>> initInvoker() {
        try {
            logger.info("try to init invoker|conf={}" + servantProxyConfig.toString());
            final List<Url> list = ParseTools.parse(servantProxyConfig);
            final ConcurrentHashSet<Invoker<T>> needRefreshInvokers = new ConcurrentHashSet<>();
            for (Url url : list) {
                try {
                    boolean active = url.getParameter(Constants.TARS_CLIENT_ACTIVE, false);
                    if (active) {
                        logger.info("try to init invoker|active={} |{}", active, url.toIdentityString());
                        needRefreshInvokers.add(create(api, url));
                    } else {
                        logger.info("inactive invoker can't to init|active={}|{}", active, url.toIdentityString());
                    }
                } catch (Throwable e) {
                    logger.error("error occurred on init invoker|" + url.toIdentityString(), e);
                }
            }
            return needRefreshInvokers;
        } catch (Throwable t) {
            logger.error("error occurred on init invoker|" + servantProxyConfig.getObjectName(), t);
        }
        return (ConcurrentHashSet<Invoker<T>>) Collections.EMPTY_SET;
    }

    private void destroy(Collection<Invoker<T>> invokers) {
        for (Invoker<?> invoker : invokers) {
            if (invoker != null) {
                logger.info("destroy reference|" + invoker);
                try {
                    allInvoker.remove(invoker);
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.error("error occurred on destroy invoker|" + invoker, t);
                }
            }
        }
    }
}
