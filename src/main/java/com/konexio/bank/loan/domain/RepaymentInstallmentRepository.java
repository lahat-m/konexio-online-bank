package com.konexio.bank.loan.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RepaymentInstallmentRepository extends JpaRepository<RepaymentInstallment, UUID> {

    List<RepaymentInstallment> findByLoanIdOrderByInstallmentNoAsc(UUID loanId);
}
