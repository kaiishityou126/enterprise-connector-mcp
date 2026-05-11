package com.sea.star.ai.ec.enterprise.connector;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.sea.star.ai.ec.enterprise.connector.domain.mapper")
public class SeaStarEnterpriseConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeaStarEnterpriseConnectorApplication.class, args);
    }

}
