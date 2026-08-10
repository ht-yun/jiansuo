package com.guquan.equity.repository;

import java.util.List;
import com.guquan.equity.model.CompanyBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyBatchCompanyRepository extends JpaRepository<CompanyBatchCompanyEntity, Long> {
    List<CompanyBatchCompanyEntity> findByJobIdOrderById(String jobId);
    List<CompanyBatchCompanyEntity> findByStatus(CompanyBatchStatus status);
    void deleteByJobId(String jobId);
}
