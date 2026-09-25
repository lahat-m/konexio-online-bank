package com.konexio.bank;

import org.springframework.boot.SpringApplication;

public class TestBankApplication {

	public static void main(String[] args) {
		SpringApplication.from(BankApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
