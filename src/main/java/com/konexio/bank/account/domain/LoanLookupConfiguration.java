package com.konexio.bank.account.domain;

import com.konexio.bank.account.LoanLookupPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the stub {@link LoanLookupPort} only when nothing else provides one.
 *
 * <p>A {@code @Bean} method rather than a {@code @Component} on the stub itself:
 * {@link ConditionalOnMissingBean} is evaluated when configuration classes are
 * processed, which is after component scanning, so it can actually see a real
 * implementation. On a scanned class the condition runs while bean definitions
 * are still being discovered and the answer depends on scan order — which is how
 * the stub managed to back off with nothing to replace it.
 */
@Configuration(proxyBeanMethods = false)
class LoanLookupConfiguration {

    @Bean
    @ConditionalOnMissingBean(LoanLookupPort.class)
    LoanLookupPort noActiveLoans() {
        return new NoActiveLoans();
    }
}
