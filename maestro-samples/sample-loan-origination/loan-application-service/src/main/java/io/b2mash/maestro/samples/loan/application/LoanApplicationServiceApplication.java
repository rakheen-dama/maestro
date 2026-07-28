package io.b2mash.maestro.samples.loan.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample — Loan application service: orchestrates the loan origination workflow
 * (verification fan-in, document collection, underwriting rounds, funding saga).
 */
@SpringBootApplication
public class LoanApplicationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanApplicationServiceApplication.class, args);
    }
}
