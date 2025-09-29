package com.jezabel.healthgen.ai;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class IpRateLimiter {
    // p.ej. 10 peticiones cada 3 minutos por IP
    private static final int LIMIT = 10;
    private static final long WINDOW_MS = 3 * 60_000L;

    private final Map<String, Deque<Long>> hits = new HashMap<>();

    public synchronized boolean allow(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
        if (q.size() >= LIMIT) return false;
        q.addLast(now);
        return true;
    }
}
