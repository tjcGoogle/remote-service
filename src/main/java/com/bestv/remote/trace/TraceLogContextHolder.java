package com.bestv.remote.trace;

/**
 * @author taojiacheng
 */
public class TraceLogContextHolder {

    private static final ThreadLocal<TraceLogContext> TRACE_LOG_CONTEXT_THREAD_LOCAL = new ThreadLocal<>();

    public static TraceLogContext getTraceLogContext() {
        return TRACE_LOG_CONTEXT_THREAD_LOCAL.get();
    }

    public static void setTraceLogContext(TraceLogContext traceLogContext) {
        TRACE_LOG_CONTEXT_THREAD_LOCAL.set(traceLogContext);
    }

    public static void removeTraceLogContext() {
        TRACE_LOG_CONTEXT_THREAD_LOCAL.remove();
    }
}
