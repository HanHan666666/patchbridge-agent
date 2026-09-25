package io.patchbridge.agent.starter.model;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelStreamListener;

import okhttp3.*;
import okhttp3.sse.*;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/** 两种模型协议共用的异步 HTTP/SSE 生命周期；不解释厂商字段，不重连或换目标。 */
final class OkHttpModelTransport {
    /** 仅描述单次流协议的完成边界。 */
    interface Decoder {
        /** 消费一条 SSE，返回是否已经遇到协议结束。 */
        boolean accept(String type, String data, ModelStreamListener listener);

        /** 连接关闭时严格确认完整结束，不接受截断响应。 */
        void finish(ModelStreamListener listener);
    }

    /** 配置在装配期固定的可复用客户端。 */
    private final OkHttpClient client;

    /** 仅配置传输超时，不持有会话或模型私有状态。 */
    OkHttpModelTransport(int connectTimeout, int readTimeout) {
        client =
                new OkHttpClient.Builder()
                        .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                        .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                        .build();
    }

    /** 统一校验地址根并追加协议固定路径，拒绝隐式携带的认证和查询字段。 */
    static String endpoint(String baseUrl, String path) {
        if (baseUrl == null) throw new IllegalArgumentException("模型 base-url 不可为空");
        String base = baseUrl.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = new URI(base);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型 base-url 不是合法 URI");
        }
        if (uri.getHost() == null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("模型 base-url 必须是无用户信息、查询或片段的绝对 HTTP(S) 地址");
        }
        return base + path;
    }

    /** 每次请求有独立终态门；取消与事件转发在同一锁内线性化。 */
    ModelCall stream(Request request, Decoder decoder, ModelStreamListener listener) {
        final class Call extends EventSourceListener implements ModelCall {
            /** 发布与取消共用的执行门。 */
            private boolean terminated;

            /** 绑定前已取消时，绑定阶段立即关闭真实连接。 */
            private EventSource source;

            /** 在异步工厂返回后绑定连接，处理极快回调中的取消。 */
            synchronized void bind(EventSource value) {
                source = value;
                if (terminated) value.cancel();
            }

            /** 取消上游并停止所有迟到通知。 */
            @Override
            public synchronized void cancel() {
                terminated = true;
                if (source != null) source.cancel();
            }

            /** 协议结束由解码器报告，传输层只管理资源和一次完成通知。 */
            @Override
            public synchronized void onEvent(
                    EventSource stream, String id, String type, String data) {
                if (terminated) return;
                try {
                    boolean complete = decoder.accept(type, data, listener);
                    if (complete && !terminated) {
                        terminated = true;
                        stream.cancel();
                        listener.onCompleted();
                    }
                } catch (RuntimeException failure) {
                    if (!terminated) {
                        terminated = true;
                        stream.cancel();
                        listener.onError(failure);
                    }
                }
            }

            /** 缺少合法结束的关闭必须失败，不能把半截回答标为成功。 */
            @Override
            public synchronized void onClosed(EventSource stream) {
                if (terminated) return;
                try {
                    decoder.finish(listener);
                    if (!terminated) {
                        terminated = true;
                        listener.onCompleted();
                    }
                } catch (RuntimeException failure) {
                    if (!terminated) {
                        terminated = true;
                        listener.onError(failure);
                    }
                }
            }

            /** 不向 Browser 暴露供应商错误正文、地址或请求凭据。 */
            @Override
            public synchronized void onFailure(
                    EventSource stream, Throwable cause, Response response) {
                if (terminated) return;
                terminated = true;
                listener.onError(
                        new ModelGatewayException(
                                response == null ? "模型上游连接失败" : "模型网关上游 HTTP " + response.code(),
                                false));
            }
        }
        Call call = new Call();
        call.bind(EventSources.createFactory(client).newEventSource(request, call));
        return call;
    }
}
