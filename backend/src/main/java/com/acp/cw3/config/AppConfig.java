package com.acp.cw3.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

@Configuration
public class AppConfig {

    /**
     * LocalStack / local dev: set {@code app.aws.use-localstack=true} and {@code app.aws.dynamodb-endpoint}
     * to the emulator URL. Production (ECS, etc.): {@code use-localstack=false} and use the default AWS
     * endpoint with the task/instance IAM role via {@link DefaultCredentialsProvider}.
     */
    @Bean
    public DynamoDbClient dynamoDbClient(
            @Value("${app.aws.use-localstack:true}") boolean useLocalstack,
            @Value("${app.aws.dynamodb-endpoint:http://localhost:4566}") String endpoint,
            @Value("${app.aws.region}") String region) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (useLocalstack) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
