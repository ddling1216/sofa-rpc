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
package com.alipay.sofa.rpc.transport.bolt;

import com.alipay.remoting.Connection;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.InvokeContext;
import com.alipay.remoting.Url;
import com.alipay.remoting.exception.ConnectionClosedException;
import com.alipay.remoting.exception.DeserializationException;
import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.exception.SerializationException;
import com.alipay.remoting.rpc.RpcClient;
import com.alipay.remoting.rpc.exception.InvokeSendFailedException;
import com.alipay.remoting.rpc.exception.InvokeServerBusyException;
import com.alipay.remoting.rpc.exception.InvokeServerException;
import com.alipay.remoting.rpc.exception.InvokeTimeoutException;
import com.alipay.sofa.rpc.client.ProviderInfo;
import com.alipay.sofa.rpc.codec.bolt.SofaRpcSerializationRegister;
import com.alipay.sofa.rpc.common.RemotingConstants;
import com.alipay.sofa.rpc.common.RpcConstants;
import com.alipay.sofa.rpc.common.utils.ClassLoaderUtils;
import com.alipay.sofa.rpc.common.utils.NetUtils;
import com.alipay.sofa.rpc.context.RpcInternalContext;
import com.alipay.sofa.rpc.core.exception.RpcErrorType;
import com.alipay.sofa.rpc.core.exception.SofaRpcException;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.core.exception.SofaTimeOutException;
import com.alipay.sofa.rpc.core.invoke.SofaResponseCallback;
import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import com.alipay.sofa.rpc.event.ClientAfterSendEvent;
import com.alipay.sofa.rpc.event.ClientBeforeSendEvent;
import com.alipay.sofa.rpc.event.ClientSyncReceiveEvent;
import com.alipay.sofa.rpc.event.EventBus;
import com.alipay.sofa.rpc.ext.Extension;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.message.ResponseFuture;
import com.alipay.sofa.rpc.message.bolt.BoltFutureInvokeCallback;
import com.alipay.sofa.rpc.message.bolt.BoltInvokerCallback;
import com.alipay.sofa.rpc.message.bolt.BoltResponseFuture;
import com.alipay.sofa.rpc.transport.AbstractChannel;
import com.alipay.sofa.rpc.transport.ClientTransport;
import com.alipay.sofa.rpc.transport.ClientTransportConfig;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 注意，bolt的实现只支持长连接共享模式。
 *
 * @author <a href="mailto:zhanggeng.zg@antfin.com">GengZhang</a>
 */
@Extension("bolt")
public class BoltClientTransport extends ClientTransport {

    /**
     * Logger for this class
     */
    private static final Logger      LOGGER          = LoggerFactory.getLogger(BoltClientTransport.class);

    protected static final RpcClient RPC_CLIENT      = new RpcClient();

    static {
        RPC_CLIENT.init();
        SofaRpcSerializationRegister.registerCustomSerializer();
    }

    /**
     * 服务端提供者信息
     */
    protected final ProviderInfo     providerInfo;

    /**
     * bolt需要的URL的缓存
     */
    protected final Url              url;

    /**
     * Connection的实时状态<br>
     * 因为一个url在bolt里对应多个connect的，但是我们禁用，只保留一个
     */
    protected volatile Connection    connection;

    /**
     * 正在发送的调用数量
     */
    protected volatile AtomicInteger currentRequests = new AtomicInteger(0);

    /**
     * Instant BoltClientTransport
     *
     * @param transportConfig 客户端配置
     */
    protected BoltClientTransport(ClientTransportConfig transportConfig) {
        super(transportConfig);
        providerInfo = transportConfig.getProviderInfo();
        url = convertProviderToUrl(transportConfig, providerInfo);
    }

    /**
     * For convert provider to bolt url.
     *
     * @param transportConfig ClientTransportConfig
     * @param providerInfo    ProviderInfo
     * @return Bolt Url
     */
    protected Url convertProviderToUrl(ClientTransportConfig transportConfig, ProviderInfo providerInfo) {
        // Url的第一个参数，如果不用事件的话，其实无所谓
        Url boltUrl = new Url(providerInfo.toString(), providerInfo.getHost(), providerInfo.getPort());

        boltUrl.setConnectTimeout(transportConfig.getConnectTimeout());
        // 注意：禁用了connectionNum方法
        // boltUrl.setConnNum(transportConfig.getConnectionNum());
        boltUrl.setConnNum(1); // 默认初始化connNum个长连接
        boltUrl.setConnWarmup(false); // true的话 
        if (RpcConstants.PROTOCOL_TYPE_BOLT.equals(providerInfo.getProtocolType())) {
            boltUrl.setProtocol(RemotingConstants.PROTOCOL_BOLT);
        } else {
            boltUrl.setProtocol(RemotingConstants.PROTOCOL_TR);
        }
        return boltUrl;
    }

    @Override
    public void connect() {
        if (connection != null) {
            if (!connection.isFine()) {
                connection.close();
                connection = null;
            }
        }
        if (connection == null) {
            synchronized (this) {
                if (connection == null) {
                    try {
                        connection = RPC_CLIENT.getConnection(url, url.getConnectTimeout());
                        // RPC_CLIENT.disableConnHeartbeat(url);
                    } catch (InterruptedException e) {
                        throw new SofaRpcRuntimeException(e);
                    } catch (RemotingException e) {
                        throw new SofaRpcRuntimeException(e);
                    }
                }
            }
        }
    }

    @Override
    public void disconnect() {
        try {
            if (connection != null) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Try disconnect client transport now. The connection is {}.",
                        NetUtils.channelToString(localAddress(), remoteAddress()));
                }
            }
            connection = null;
            RPC_CLIENT.closeConnection(url);
        } catch (Exception e) {
            throw new SofaRpcRuntimeException("", e);
        }
    }

    @Override
    public void destroy() {
        disconnect();
    }

    @Override
    public boolean isAvailable() {
        return connection != null && connection.isFine();
    }

    @Override
    public void setChannel(AbstractChannel channel) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public AbstractChannel getChannel() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public int currentRequests() {
        return currentRequests.get();
    }

    @Override
    public ResponseFuture asyncSend(SofaRequest request, int timeout) throws SofaRpcException {
        checkConnection();
        RpcInternalContext context = RpcInternalContext.getContext();
        InvokeContext boltInvokeContext = createInvokeContext(request);
        try {
            beforeSend(context, request);
            // 复制一份上下文
            context = context.clone();
            boltInvokeContext.put(RemotingConstants.INVOKE_CTX_RPC_CTX, context);
            return doInvokeAsync(request, context, boltInvokeContext, timeout);
        } catch (Exception e) {
            throw convertToRpcException(e);
        } finally {
            afterSend(context, boltInvokeContext, request);
        }
    }

    /**
     * 异步调用
     *
     * @param request       请求对象
     * @param rpcContext    RPC内置上下文
     * @param invokeContext 调用上下文
     * @param timeoutMillis 超时时间（毫秒）
     * @throws RemotingException    远程调用异常
     * @throws InterruptedException 中断异常
     * @since 5.2.0
     */
    protected ResponseFuture doInvokeAsync(SofaRequest request, RpcInternalContext rpcContext,
                                           InvokeContext invokeContext, int timeoutMillis)
        throws RemotingException, InterruptedException {
        SofaResponseCallback listener = request.getSofaResponseCallback();
        if (listener != null) {
            // callback调用
            InvokeCallback callback = new BoltInvokerCallback(transportConfig.getConsumerConfig(), providerInfo,
                listener, request, rpcContext, ClassLoaderUtils.getCurrentClassLoader());
            // 发起调用
            RPC_CLIENT.invokeWithCallback(url, request, invokeContext, callback, timeoutMillis);
            return null;
        } else {
            // future 转为 callback
            BoltResponseFuture future = new BoltResponseFuture(request, timeoutMillis);
            InvokeCallback callback = new BoltFutureInvokeCallback(transportConfig.getConsumerConfig(), providerInfo,
                future, request, rpcContext, ClassLoaderUtils.getCurrentClassLoader());
            // 发起调用
            RPC_CLIENT.invokeWithCallback(url, request, invokeContext, callback, timeoutMillis);
            // 记录到上下文 传递出去
            RpcInternalContext.getContext().setFuture(future);
            future.setSentTime();
            return future;
        }
    }

    @Override
    public SofaResponse syncSend(SofaRequest request, int timeout) throws SofaRpcException {
        checkConnection();
        RpcInternalContext context = RpcInternalContext.getContext();
        InvokeContext boltInvokeContext = createInvokeContext(request);
        SofaResponse response = null;
        SofaRpcException throwable = null;
        try {
            beforeSend(context, request);
            response = doInvokeSync(request, boltInvokeContext, timeout);
            return response;
        } catch (Exception e) { // 其它异常
            throwable = convertToRpcException(e);
            throw throwable;
        } finally {
            afterSend(context, boltInvokeContext, request);
            if (EventBus.isEnable(ClientSyncReceiveEvent.class)) {
                EventBus.post(new ClientSyncReceiveEvent(transportConfig.getConsumerConfig(),
                    providerInfo, request, response, throwable));
            }
        }
    }

    /**
     * 同步调用
     *
     * @param request       请求对象
     * @param invokeContext 调用上下文
     * @param timeoutMillis 超时时间（毫秒）
     * @return 返回对象
     * @throws RemotingException    远程调用异常
     * @throws InterruptedException 中断异常
     * @since 5.2.0
     */
    protected SofaResponse doInvokeSync(SofaRequest request, InvokeContext invokeContext, int timeoutMillis)
        throws RemotingException, InterruptedException {
        return (SofaResponse) RPC_CLIENT.invokeSync(url, request, invokeContext, timeoutMillis);
    }

    @Override
    public void oneWaySend(SofaRequest request, int timeout) throws SofaRpcException {
        checkConnection();
        RpcInternalContext context = RpcInternalContext.getContext();
        InvokeContext invokeContext = createInvokeContext(request);
        SofaRpcException throwable = null;
        try {
            beforeSend(context, request);
            doOneWay(request, invokeContext, timeout);
        } catch (Exception e) { // 其它异常
            throwable = convertToRpcException(e);
            throw throwable;
        } finally {
            afterSend(context, invokeContext, request);
            if (EventBus.isEnable(ClientSyncReceiveEvent.class)) {
                EventBus.post(new ClientSyncReceiveEvent(transportConfig.getConsumerConfig(),
                    providerInfo, request, null, throwable));
            }
        }
    }

    /**
     * 同步调用
     *
     * @param request       请求对象
     * @param invokeContext 调用上下文
     * @param timeoutMillis 超时时间（毫秒）
     * @throws RemotingException    远程调用异常
     * @throws InterruptedException 中断异常
     * @since 5.2.0
     */
    protected void doOneWay(SofaRequest request, InvokeContext invokeContext, int timeoutMillis)
        throws RemotingException, InterruptedException {
        RPC_CLIENT.oneway(url, request, invokeContext);
    }

    /**
     * 转换调用出现的异常为RPC异常
     *
     * @param e 异常
     * @return RPC异常
     */
    protected SofaRpcException convertToRpcException(Exception e) {
        SofaRpcException exception;
        // 超时
        if (e instanceof InvokeTimeoutException) {
            exception = new SofaTimeOutException(e);
        }
        // 服务器忙
        else if (e instanceof InvokeServerBusyException) {
            exception = new SofaRpcException(RpcErrorType.SERVER_BUSY, e);
        }
        // 序列化
        else if (e instanceof SerializationException) {
            boolean isServer = ((SerializationException) e).isServerSide();
            exception = isServer ? new SofaRpcException(RpcErrorType.CLIENT_SERIALIZE, e)
                : new SofaRpcException(RpcErrorType.SERVER_SERIALIZE, e);
        }
        // 反序列化
        else if (e instanceof DeserializationException) {
            boolean isServer = ((DeserializationException) e).isServerSide();
            exception = isServer ? new SofaRpcException(RpcErrorType.SERVER_DESERIALIZE, e)
                : new SofaRpcException(RpcErrorType.CLIENT_DESERIALIZE, e);
        }
        // 长连接断连
        else if (e instanceof ConnectionClosedException) {
            exception = new SofaRpcException(RpcErrorType.CLIENT_NETWORK, e);
        }
        // 客户端发送失败
        else if (e instanceof InvokeSendFailedException) {
            exception = new SofaRpcException(RpcErrorType.CLIENT_NETWORK, e);
        }
        // 服务端未知异常
        else if (e instanceof InvokeServerException) {
            exception = new SofaRpcException(RpcErrorType.SERVER_UNDECLARED_ERROR, e.getCause());
        }
        // 客户端未知
        else {
            exception = new SofaRpcException(RpcErrorType.CLIENT_UNDECLARED_ERROR, e);
        }
        return exception;
    }

    protected InvokeContext createInvokeContext(SofaRequest request) {
        InvokeContext invokeContext = new InvokeContext();
        invokeContext.put(RemotingConstants.INVOKE_CTX_SERIALIZE_FACTORY_TYPE, request.getSerializeFactoryType());
        invokeContext.put(InvokeContext.BOLT_CUSTOM_SERIALIZER, request.getSerializeType());
        invokeContext.put(RemotingConstants.HEAD_TARGET_SERVICE, request.getTargetServiceUniqueName());
        invokeContext.put(RemotingConstants.HEAD_METHOD_NAME, request.getMethodName());
        return invokeContext;
    }

    /**
     * 调用前设置一些属性
     *
     * @param context RPC上下文
     * @param request 请求对象
     */
    protected void beforeSend(RpcInternalContext context, SofaRequest request) {
        currentRequests.incrementAndGet();
        context.setLocalAddress(localAddress());
        if (EventBus.isEnable(ClientBeforeSendEvent.class)) {
            EventBus.post(new ClientBeforeSendEvent(request));
        }
    }

    /**
     * 调用后设置一些属性（注意，在异步的情况较多下）
     *
     * @param context       RPC上下文
     * @param invokeContext bolt调用上下文
     * @param request       请求对象
     */
    protected void afterSend(RpcInternalContext context, InvokeContext invokeContext, SofaRequest request) {
        currentRequests.decrementAndGet();
        if (RpcInternalContext.isAttachmentEnable()) {
            putToContextIfNotNull(invokeContext, InvokeContext.CLIENT_CONN_CREATETIME, context,
                RpcConstants.INTERNAL_KEY_CONN_CREATE_TIME);
        }
        if (EventBus.isEnable(ClientAfterSendEvent.class)) {
            EventBus.post(new ClientAfterSendEvent(request));
        }
    }

    @Override
    public void receiveRpcResponse(SofaResponse response) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void handleRpcRequest(SofaRequest request) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return connection == null ? null : connection.getRemoteAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return connection == null ? null : connection.getLocalAddress();
    }

    protected void checkConnection() throws SofaRpcException {
        if (connection == null) {
            throw new SofaRpcException(RpcErrorType.CLIENT_NETWORK, "connection is null");
        }
        if (!connection.isFine()) {
            throw new SofaRpcException(RpcErrorType.CLIENT_NETWORK, "connection is not fine");
        }
    }

    protected void putToContextIfNotNull(InvokeContext invokeContext, String oldKey,
                                         RpcInternalContext context, String key) {
        Object value = invokeContext.get(oldKey);
        if (value != null) {
            context.setAttachment(key, value);
        }
    }
}
