package com.bestv.remote.trace;

import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author taojiacheng
 */
public class TraceLogInterceptorAdapter extends HandlerInterceptorAdapter {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sn = request.getParameter("sn");
        String userId = request.getParameter("userId");
        TraceLogContext traceLogContext = TraceLogContext.builder()
                .sn(sn)
                .userId(userId)
                .build();
        TraceLogContextHolder.setTraceLogContext(traceLogContext);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TraceLogContextHolder.removeTraceLogContext();
    }
}
