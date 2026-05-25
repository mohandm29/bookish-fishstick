// FIXTURE: R16 — find() / get() in a write path without LockModeType
// Expected severity: MEDIUM
// Expected finding: find() in a write path without LockModeType leaves a race window between read and update

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.math.BigDecimal;

public class AccountService {

    @PersistenceContext
    private EntityManager em;

    public void debit(Long accountId, BigDecimal amount) {
        Account account = em.find(Account.class, accountId);
        account.setBalance(account.getBalance().subtract(amount));
        em.persist(account);
    }
}
