package one.neo.graph;

import one.neo.config.GraphProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Realtime transaction scorer: stateless rules + in-memory sliding window.
 * Thresholds from GraphProperties (env-tunable). Replace the window with
 * Kafka/Redis when volume grows - the API stays identical.
 */
@org.springframework.stereotype.Service
public class ScoringService {

    private final GraphProperties props;

    private final ConcurrentLinkedDeque<Map<String, Object>> window = new ConcurrentLinkedDeque<>();
    private final List<Map<String, Object>> alerts = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public ScoringService(GraphProperties props) {
        this.props = props;
    }

    static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    public Map<String, Object> score(Map<String, Object> txn) {
        long now = System.currentTimeMillis();
        txn = new LinkedHashMap<>(txn);
        txn.putIfAbsent("receivedAt", now);
        window.addLast(txn);
        long cutoff = now - (long) props.getAml().getVelocityWindowMinutes() * 60_000L;
        while (!window.isEmpty()) {
            Map<String, Object> f = window.peekFirst();
            Object ra = f.get("receivedAt");
            long t = ra instanceof Number n ? n.longValue() : now;
            if (t < cutoff) window.pollFirst(); else break;
        }
        if (window.size() > 20000) {
            for (int i = 0; i < 5000; i++) window.pollFirst();
        }

        String acid = String.valueOf(txn.getOrDefault("acid", txn.getOrDefault("accountNum", "?")));
        double amount = num(txn.get("amount"));
        List<String> fired = new ArrayList<>();
        int score = 0;

        if (amount >= props.getAml().getLargeAmountKes()) { fired.add("LARGE_AMOUNT"); score += 60; }
        else if (amount >= props.getAml().getStructuringLowKes()) { fired.add("STRUCTURING_BAND"); score += 30; }

        long cnt = window.stream().filter(e ->
                acid.equals(String.valueOf(e.getOrDefault("acid", e.getOrDefault("accountNum", "?"))))).count();
        if (cnt >= props.getAml().getVelocityCount()) {
            fired.add("VELOCITY_" + cnt + "_IN_" + props.getAml().getVelocityWindowMinutes() + "M");
            score += 40;
        }

        Long prev = lastSeen.put(acid + "|" + amount, now);
        if (prev != null && now - prev < 5 * 60_000L) { fired.add("RAPID_REPEAT"); score += 20; }

        if ("Y".equalsIgnoreCase(String.valueOf(txn.get("isOfficeAccount")))) {
            fired.add("OFFICE_ACCOUNT");
            score -= 20;
        }

        score = Math.max(0, Math.min(100, score));
        String level = score >= 80 ? "HIGH" : score >= 40 ? "MEDIUM" : score > 0 ? "LOW" : "CLEAR";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("acid", acid);
        out.put("tranId", String.valueOf(txn.getOrDefault("tranId", "?")));
        out.put("amount", amount);
        out.put("riskScore", score);
        out.put("level", level);
        out.put("rules", fired);
        out.put("at", now);
        if (!fired.isEmpty()) {
            Map<String, Object> alert = new LinkedHashMap<>(out);
            alert.put("rule", String.join(",", fired));
            alerts.add(alert);
            if (alerts.size() > 1000) alerts.remove(0);
        }
        return out;
    }

    public List<Map<String, Object>> recentAlerts(int limit) {
        synchronized (alerts) {
            int n = Math.min(Math.max(1, limit), alerts.size());
            return new ArrayList<>(alerts.subList(alerts.size() - n, alerts.size()));
        }
    }
}
