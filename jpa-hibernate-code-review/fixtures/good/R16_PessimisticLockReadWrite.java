// FIXTURE: R16 — clean implementation
// This file demonstrates the CORRECT pattern for rule R16.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R16.
package com.example.fixtures.good;

import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class AccountService {

    @PersistenceContext
    private EntityManager em;

    // CORRECT: PESSIMISTIC_WRITE on the read prevents lost updates for a known
    // hot row (account balances). The lock is held for the duration of the TX
    // and released on commit.
    @Transactional
    public void debit(Long accountId, BigDecimal amount) {
        Account acct = em.find(
            Account.class,
            accountId,
            LockModeType.PESSIMISTIC_WRITE
        );
        acct.debit(amount);
    }
}
