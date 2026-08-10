package com.guquan.equity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.guquan.equity.provider.GsxtAccessControlProperties;
import com.guquan.equity.repository.GsxtAccessControlEntity;
import com.guquan.equity.repository.GsxtAccessControlRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GsxtAccessControlServiceTest {

    private final GsxtAccessControlRepository repository = mock(GsxtAccessControlRepository.class);
    private final GsxtAccessControlProperties properties = new GsxtAccessControlProperties();
    private final GsxtAccessControlEntity state = new GsxtAccessControlEntity();
    private GsxtAccessControlService service;

    @BeforeEach
    void setUp() {
        state.setId(1L);
        state.setHourlyWindowStartedAt(LocalDateTime.now());
        properties.setMinCompanyIntervalSeconds(2);
        properties.setMaxCompanyIntervalSeconds(2);
        properties.setMaxCompaniesPerHour(2);
        properties.setBlockedCooldownHours(3);
        when(repository.findById(1L)).thenReturn(Optional.of(state));
        when(repository.save(any(GsxtAccessControlEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new GsxtAccessControlService(repository, properties);
    }

    @Test
    void reservesAWaitBeforeAnotherCompanyCanStart() {
        service.beginCompanyVisit();

        assertThat(state.getNextAllowedAt()).isAfter(LocalDateTime.now());
        assertThatThrownBy(service::requireSessionMayStart)
                .isInstanceOf(GsxtAccessRateLimitException.class)
                .hasMessageContaining("访问保护等待中");
    }

    @Test
    void officialBlockCreatesPersistentCooldown() {
        service.block("IP 请求异常");

        assertThat(state.getBlockedUntil()).isAfter(LocalDateTime.now().plusHours(2));
        assertThatThrownBy(service::beginCompanyVisit)
                .isInstanceOf(GsxtAccessRateLimitException.class)
                .hasMessageContaining("官网已报告访问限制");
    }

    @Test
    void hourlyLimitExtendsTheWaitToWindowEnd() {
        state.setCompletedCompaniesInWindow(1);
        long waitMillis = service.completeCompanyVisit();

        assertThat(state.getCompletedCompaniesInWindow()).isEqualTo(2);
        assertThat(waitMillis).isGreaterThan(50 * 60 * 1000L);
    }
}
