package com.guquan.equity.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "gsxt_access_control")
public class GsxtAccessControlEntity {

    @Id
    private Long id;

    private LocalDateTime nextAllowedAt;
    private LocalDateTime blockedUntil;
    private LocalDateTime hourlyWindowStartedAt;
    private int completedCompaniesInWindow;
    private LocalDateTime lastCompanyStartedAt;
    private LocalDateTime lastCompanyCompletedAt;

    @Column(length = 1000)
    private String lastBlockReason;
}
