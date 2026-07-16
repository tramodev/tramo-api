package com.mypath.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.core.StreamReadConstraints;

@SpringBootApplication
@EnableScheduling
public class MypathBackendApplication {

	private static final int MAX_JSON_STRING_LENGTH = 50_000_000;

	public static void main(String[] args) {
		StreamReadConstraints.overrideDefaultStreamReadConstraints(
				StreamReadConstraints.builder().maxStringLength(MAX_JSON_STRING_LENGTH).build()
		);
		SpringApplication.run(MypathBackendApplication.class, args);
	}

}
