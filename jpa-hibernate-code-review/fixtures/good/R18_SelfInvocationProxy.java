// FIXTURE: R18 — clean implementation
// This file demonstrates the CORRECT pattern for rule R18.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R18.
package com.example.fixtures.good;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// CORRECT: split into two beans so the call goes through the Spring proxy and
// REQUIRES_NEW actually starts a new transaction. Self-invocation (this.inner())
// bypasses the proxy and ignores @Transactional metadata on `inner`.
@Service
public class OrderService {

    private final OrderInnerService inner;

    public OrderService(OrderInnerService inner) { this.inner = inner; }

    @Transactional
    public void outer() {
        inner.inner();
    }
}

@Service
class OrderInnerService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() {
        // runs in its own transaction — proxy intercept honoured
    }
}
