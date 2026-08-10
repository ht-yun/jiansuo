package com.guquan.equity.service;

import com.guquan.equity.provider.GsxtAccessControlProperties;
import com.guquan.equity.repository.GsxtAccessControlEntity;
import com.guquan.equity.repository.GsxtAccessControlRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local, persistent guardrail for requests made to the public GSXT website.
 * It deliberately favors waiting over retrying when the official site signals a block.
 */
@Service
public class GsxtAccessControlService {

    private static final long STATE_ID = 1L;
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final GsxtAccessControlRepository repository;
    private final GsxtAccessControlProperties properties;

    public GsxtAccessControlService(GsxtAccessControlRepository repository,
            GsxtAccessControlProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public synchronized void requireSessionMayStart() {
        GsxtAccessControlEntity state = state();
        LocalDateTime now = LocalDateTime.now();
        resetHourlyWindowIfNeeded(state, now);
        requireAvailable(state, now);
        repository.save(state);
    }

    /** Reserves a time slot before the extension asks the user to query one company. */
    @Transactional
    public synchronized void beginCompanyVisit() {
        GsxtAccessControlEntity state = state();
        LocalDateTime now = LocalDateTime.now();
        resetHourlyWindowIfNeeded(state, now);
        requireAvailable(state, now);
        state.setLastCompanyStartedAt(now);
        state.setNextAllowedAt(laterOf(state.getNextAllowedAt(), now.plusSeconds(randomIntervalSeconds())));
        repository.save(state);
    }

    /** Records a saved company and returns the enforced wait before the next one. */
    @Transactional
    public synchronized long completeCompanyVisit() {
        GsxtAccessControlEntity state = state();
        LocalDateTime now = LocalDateTime.now();
        resetHourlyWindowIfNeeded(state, now);
        state.setLastCompanyCompletedAt(now);
        state.setCompletedCompaniesInWindow(state.getCompletedCompaniesInWindow() + 1);

        LocalDateTime next = now.plusSeconds(randomIntervalSeconds());
        if (state.getCompletedCompaniesInWindow() >= Math.max(1, properties.getMaxCompaniesPerHour())) {
            next = laterOf(next, state.getHourlyWindowStartedAt().plusHours(1));
        }
        state.setNextAllowedAt(laterOf(state.getNextAllowedAt(), next));
        repository.save(state);
        return Math.max(1_000L, Duration.between(now, state.getNextAllowedAt()).toMillis() + 500L);
    }

    /** Locks all new automated visits after an official access-block response. */
    @Transactional
    public synchronized void block(String reason) {
        GsxtAccessControlEntity state = state();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime blockedUntil = now.plusHours(Math.max(1, properties.getBlockedCooldownHours()));
        state.setBlockedUntil(blockedUntil);
        state.setNextAllowedAt(laterOf(state.getNextAllowedAt(), blockedUntil));
        state.setLastBlockReason(trim(reason));
        repository.save(state);
    }

    private GsxtAccessControlEntity state() {
        return repository.findById(STATE_ID).orElseGet(() -> {
            GsxtAccessControlEntity created = new GsxtAccessControlEntity();
            created.setId(STATE_ID);
            created.setHourlyWindowStartedAt(LocalDateTime.now());
            return created;
        });
    }

    private void resetHourlyWindowIfNeeded(GsxtAccessControlEntity state, LocalDateTime now) {
        if (state.getHourlyWindowStartedAt() == null
                || !state.getHourlyWindowStartedAt().plusHours(1).isAfter(now)) {
            state.setHourlyWindowStartedAt(now);
            state.setCompletedCompaniesInWindow(0);
        }
    }

    private void requireAvailable(GsxtAccessControlEntity state, LocalDateTime now) {
        if (state.getBlockedUntil() != null && state.getBlockedUntil().isAfter(now)) {
            throw limited("官网已报告访问限制。为保护当前 IP，系统已暂停自动访问至 ", state.getBlockedUntil());
        }
        if (state.getNextAllowedAt() != null && state.getNextAllowedAt().isAfter(now)) {
            throw limited("访问保护等待中，请在 ", state.getNextAllowedAt());
        }
    }

    private GsxtAccessRateLimitException limited(String prefix, LocalDateTime retryAt) {
        return new GsxtAccessRateLimitException(prefix + retryAt.format(DISPLAY_TIME) + " 后再开始。", retryAt);
    }

    private int randomIntervalSeconds() {
        int min = Math.max(1, properties.getMinCompanyIntervalSeconds());
        int max = Math.max(min, properties.getMaxCompanyIntervalSeconds());
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private LocalDateTime laterOf(LocalDateTime first, LocalDateTime second) {
        return first == null || second.isAfter(first) ? second : first;
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) return "官网返回访问限制";
        String result = value.trim();
        return result.length() <= 1000 ? result : result.substring(0, 1000);
    }
}
